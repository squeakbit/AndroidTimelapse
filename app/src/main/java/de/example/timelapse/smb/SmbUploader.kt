package de.example.timelapse.smb
import android.content.Context
import de.example.timelapse.*
import de.example.timelapse.data.*
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
data class UploadResult(val uploaded:Int,val failed:Int,val removed:Int=0)
class SmbUploader(private val context:Context){
 suspend fun uploadPendingPhotos():UploadResult=withContext(Dispatchers.IO){
  val s=SettingsManager(context); val dao=AppDatabase.getInstance(context).photoDao(); val pending=dao.getPendingPhotos()
  Log.i("Timelapse","upload run: ${pending.size} pending file(s) found")
  if(pending.isEmpty())return@withContext UploadResult(0,0,0)
  var u=0;var f=0;var r=0; val client=SMBClient()
  try{
   client.connect(s.smbHost).use{connection->
    val sec=SecureSecrets(context); val user=sec.smbUsername.ifBlank{s.smbUsername};val pass=sec.smbPassword.ifBlank{s.smbPassword}
    connection.authenticate(AuthenticationContext(user,pass.toCharArray(),s.smbDomain.ifBlank{null})).use{session->
     (session.connectShare(s.smbShare) as DiskShare).use{share->
      for(p in pending) when(uploadOne(share,p,dao,s)){
       UploadOutcome.SUCCESS->u++
       UploadOutcome.FAILED->f++
       UploadOutcome.REMOVED->r++
      }
     }
    }
   }
  }catch(_:Throwable){f+=pending.size-u-r}finally{client.close()}
  
  if (s.deleteAfterUpload) cleanupLocalFiles(dao)
  
  UploadResult(u,f,r)
 }

 private suspend fun cleanupLocalFiles(dao: PhotoDao) {
  try {
   val uploaded = dao.getAllUploadedPhotos()
   if (uploaded.isEmpty()) return
   
   // Group by camera label (e.g., "B0", "F1")
   val groups = uploaded.groupBy { it.fileName.substringBefore('_') }
   
   for ((_, photos) in groups) {
    // Sort by capture time descending
    val sorted = photos.sortedByDescending { it.capturedAt }
    
    // Delete local files for all EXCEPT the latest one
    for (i in 1 until sorted.size) {
     val p = sorted[i]
     try {
      context.contentResolver.delete(Uri.parse(p.localPath), null, null)
     } catch (_: Throwable) {}
    }
   }
  } catch (t: Throwable) {
   Log.w("Timelapse", "Cleanup of local files failed: ${t.message}")
  }
 }

 private enum class UploadOutcome{SUCCESS,FAILED,REMOVED}
 private suspend fun uploadOne(share:DiskShare,p:PhotoEntity,dao:PhotoDao,s:SettingsManager):UploadOutcome{
  val uri=Uri.parse(p.localPath)
  // Local file was deleted outside the app (e.g. manually on the device) -
  // retrying forever would keep it stuck as "pending" indefinitely, so we
  // drop it from the database instead.
  val readable=try{context.contentResolver.openInputStream(uri)?.use{true} ?: false}catch(_:Throwable){false}
  if(!readable){dao.delete(p);return UploadOutcome.REMOVED}
  return try{
   val date=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date(p.capturedAt))
   val dir=listOf(s.smbRemoteDirectory.trim('/'),date).filter{it.isNotBlank()}.joinToString("/")
   ensureDir(share,dir);val remote="$dir/${p.fileName}"
   context.contentResolver.openInputStream(uri)!!.use{input->
    share.openFile(remote,setOf(AccessMask.FILE_WRITE_DATA),null,SMB2ShareAccess.ALL,SMB2CreateDisposition.FILE_OVERWRITE_IF,null).use{f->
     f.getOutputStream().use{out->input.copyTo(out,65536)}
    }
   }
   dao.update(p.copy(uploadedAt=System.currentTimeMillis(),uploadAttempts=p.uploadAttempts+1,lastUploadError=null,remotePath=remote))
   
   if(s.deleteAfterUpload) {
    // PROTECT GHOST IMAGE: We only delete the file if it is NOT the 
    // single most recent photo taken by this specific camera.
    val label = p.fileName.substringBefore('_')
    val lastPhoto = dao.getLastPhotoByCameraLabel(label)
    if (lastPhoto != null && (lastPhoto.id != p.id)) {
     try { context.contentResolver.delete(uri, null, null) } catch (_: Throwable) {}
    }
   }
   UploadOutcome.SUCCESS
  }catch(t:Throwable){dao.update(p.copy(uploadAttempts=p.uploadAttempts+1,lastUploadError=describe(t)));UploadOutcome.FAILED}
 }
 private fun ensureDir(share:DiskShare,path:String){var cur="";for(x in path.split("/").filter{it.isNotBlank()}){cur=if(cur.isBlank())x else "$cur/$x";if(!share.folderExists(cur))share.mkdir(cur)}}

 /** Builds a readable "ExceptionType: message (caused by CauseType: message)" chain for diagnostics. */
 private fun describe(t: Throwable): String {
  val parts = mutableListOf<String>()
  var cur: Throwable? = t
  var depth = 0
  while (cur != null && depth < 4) {
   parts.add("${cur.javaClass.simpleName}: ${cur.message ?: "(keine Meldung)"}")
   cur = cur.cause?.takeIf { it !== cur }
   depth++
  }
  return parts.joinToString(" ← ")
 }

 suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
  val s = SettingsManager(context)
  if (s.smbHost.isBlank()) return@withContext Result.failure(IllegalStateException("SMB Server ist leer"))
  if (s.smbShare.isBlank()) return@withContext Result.failure(IllegalStateException("SMB Share ist leer"))
  val client = SMBClient()
  try {
   client.connect(s.smbHost).use { connection ->
    val sec = SecureSecrets(context)
    val user = sec.smbUsername.ifBlank { s.smbUsername }
    val pass = sec.smbPassword.ifBlank { s.smbPassword }
    val domain = s.smbDomain.ifBlank { null }
    val dialect = try { connection.negotiatedProtocol.dialect.toString() } catch (_: Throwable) { "unbekannt" }
    try {
     connection.authenticate(AuthenticationContext(user, pass.toCharArray(), domain)).use { session ->
      (session.connectShare(s.smbShare) as DiskShare).use { share ->
       val dir = s.smbRemoteDirectory.trim('/')
       if (dir.isNotBlank()) ensureDir(share, dir)
       val testDir = listOf(dir, ".timelapse_test").filter { it.isNotBlank() }.joinToString("/")
       ensureDir(share, testDir)
       val testFile = "$testDir/write_test.tmp"
       try {
        share.openFile(
         testFile,
         setOf(AccessMask.FILE_WRITE_DATA),
         null,
         SMB2ShareAccess.ALL,
         SMB2CreateDisposition.FILE_OVERWRITE_IF,
         null
        ).use { f ->
         f.getOutputStream().use { out -> out.write("timelapse connectivity test".toByteArray()) }
        }
        try { share.rm(testFile) } catch (_: Throwable) {}
        Result.success("Verbindung OK (Dialekt $dialect): Share '${s.smbShare}' erreichbar, Schreibtest in '$testDir' erfolgreich")
       } catch (t: Throwable) {
        Result.failure(IllegalStateException("Share erreichbar (Dialekt $dialect), aber Schreibtest fehlgeschlagen: ${describe(t)}", t))
       }
      }
     }
    } catch (t: Throwable) {
     Result.failure(IllegalStateException("Auth/Verbindungsfehler (ausgehandelter Dialekt: $dialect, Domain: ${domain ?: "null"}): ${describe(t)}", t))
    }
   }
  } catch (t: Throwable) {
   Result.failure(IllegalStateException(describe(t), t))
  } finally {
   client.close()
  }
 }
}
