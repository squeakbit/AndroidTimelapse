package de.example.timelapse.camera

data class CameraInfo(
    val id: String,
    val facing: Int,
    val orientation: Int,
    val logicalMultiCamera: Boolean,
    val sizes: List<SizeOption>,
    val previewSizes: List<SizeOption> = emptyList()
)

data class SizeOption(val width: Int, val height: Int) {
    override fun toString() = "$width × $height"
}
