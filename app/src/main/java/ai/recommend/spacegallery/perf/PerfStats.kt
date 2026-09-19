package ai.recommend.spacegallery.perf

import java.util.Locale

/**
 * Накопитель длительностей по этапам пайплайна (для диагностики скорости индексации).
 * Накладные расходы — пара вызовов System.nanoTime() на этап.
 */
object PerfStats {

    private class Stage(var count: Long = 0, var totalNs: Long = 0, var maxNs: Long = 0)

    private val stages = LinkedHashMap<String, Stage>()

    inline fun <T> measure(stage: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(stage, System.nanoTime() - start)
        }
    }

    @Synchronized
    fun record(stage: String, ns: Long) {
        val s = stages.getOrPut(stage) { Stage() }
        s.count++
        s.totalNs += ns
        if (ns > s.maxNs) s.maxNs = ns
    }

    /** Таблица «этап / кол-во / среднее / макс / сумма / доля от [totalStage]» и сброс счётчиков. */
    @Synchronized
    fun reportAndReset(totalStage: String): String {
        val base = stages[totalStage]?.totalNs?.takeIf { it > 0 } ?: 1L
        val sb = StringBuilder()
        sb.append(String.format(Locale.ROOT, "%-18s %6s %9s %9s %9s %6s%n", "stage", "n", "avg,ms", "max,ms", "sum,s", "share"))
        for ((name, s) in stages) {
            sb.append(
                String.format(
                    Locale.ROOT,
                    "%-18s %6d %9.1f %9.1f %9.2f %5.1f%%%n",
                    name, s.count, s.totalNs / 1e6 / s.count, s.maxNs / 1e6, s.totalNs / 1e9, 100.0 * s.totalNs / base,
                )
            )
        }
        stages.clear()
        return sb.toString()
    }
}
