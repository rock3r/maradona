package dev.maradona.sample

import platform.Foundation.NSLog

actual fun oracleLog(message: String) {
    NSLog(message)
}
