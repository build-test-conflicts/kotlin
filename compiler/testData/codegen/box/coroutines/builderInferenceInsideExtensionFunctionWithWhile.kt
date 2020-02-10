// IGNORE_BACKEND_FIR: JVM_IR
// WITH_RUNTIME
// WITH_COROUTINES
// COMMON_COROUTINES_TEST
// IGNORE_BACKEND_FIR: JVM_IR

fun <T> Collection<T>.lazyClosure(f: (T) -> Collection<T>): Sequence<T> = sequence {
    if (size == 0) return@sequence
    var sizeBeforeIteration = 0

    yieldAll(this@lazyClosure)
    var yieldedCount = size
    var elementsToCheck = this@lazyClosure

    while (yieldedCount > sizeBeforeIteration) {
        val toAdd = hashSetOf<T>()
        elementsToCheck.forEach {
            val neighbours = f(it)
            yieldAll(neighbours)
            yieldedCount += neighbours.size
            toAdd.addAll(neighbours)
        }
        elementsToCheck = toAdd
        sizeBeforeIteration = yieldedCount
    }
}

fun box(): String {
    val ls = listOf(listOf("OK"))
    ls.lazyClosure {
        ls.
    }
    return "OK"
}