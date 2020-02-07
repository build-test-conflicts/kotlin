// !LANGUAGE: +NewInference

fun <T> noInline(f: () -> T): T = f()
inline fun <T, R> myWith(receiver: T, block: T.() -> R): R = receiver.block()

fun test() {
    noInline {
        with("str") {
            fun local() {}

            if (true) local() else noInline(::local)
        }
    }
}

fun box(): String {
    test()
    return "OK"
}
