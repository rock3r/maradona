package dev.maradona.parse

class MaradonaParseException(
    val line: Int,
    message: String,
) : Exception("Line $line: $message")
