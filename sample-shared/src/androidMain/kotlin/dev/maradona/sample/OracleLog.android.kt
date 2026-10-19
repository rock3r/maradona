package dev.maradona.sample

/** stdout is redirected to logcat (tag `System.out`), which the capture script greps. */
actual fun oracleLog(message: String) {
    println(message)
}
