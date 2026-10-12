package hk.edu.polyu.genai

object UrlRouting {
    fun isWebScheme(scheme: String?): Boolean {
        val normalized = scheme?.lowercase()
        return normalized == "http" || normalized == "https"
    }
}
