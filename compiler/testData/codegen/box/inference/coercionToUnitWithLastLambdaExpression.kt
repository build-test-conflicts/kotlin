fun <T> myRun(action: () -> T): T = action()
fun foo(): String = "foo"

var result = "fail"

fun test(n: Number, b: Boolean) {
    n.let {
        if (b) return@let

        myRun {
            result = "OK"
            foo()
        }
    }
}

fun box(): String {
    test(42, false)
    return result
}