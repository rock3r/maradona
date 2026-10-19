package dev.maradona.sample

/**
 * Reports an oracle render to the platform log, where the capture scripts read
 * it back (the app cannot know its own capture framing otherwise).
 *
 * Android goes through stdout (logcat tag `System.out`); iOS needs `NSLog`
 * because plain stdout never reaches the unified log.
 */
expect fun oracleLog(message: String)
