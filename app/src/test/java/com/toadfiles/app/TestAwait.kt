package com.toadfiles.app

/**
 * The one polling wait shared by every ViewModel/UI test (audit A27): a 10-second
 * deadline with a 10 ms poll, failing when the condition never materializes. This
 * helper was copy-pasted as a private member into ten test files; the copies differed
 * in nothing but comments, so they now share this definition.
 *
 * Same semantics as the removed copies: returns as soon as [block] turns true;
 * throws IllegalStateException once the deadline passes.
 */
internal fun await(timeoutMs: Long = 10_000, block: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!block()) {
        check(System.currentTimeMillis() < deadline) { "condition not met within timeout" }
        Thread.sleep(10)
    }
}
