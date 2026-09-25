package ai.recommend.spacegallery.work

import ai.recommend.spacegallery.SpaceGalleryApp
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Темп индексации: на что мы имеем право прямо сейчас — молотить во весь процессор или
 * работать незаметно.
 *
 * Поводы для полного темпа ровно два: телефон стоит на зарядке и система считает его
 * простаивающим (ночь), либо приложение открыто на экране — пользователь сам смотрит на
 * прогресс и ждёт. Во всех остальных случаях индексация идёт тихо: меньше потоков и паузы
 * между кадрами, чтобы занимать около четверти времени.
 *
 * Зачем паузы вообще: замеры (docs/performance.md) показали, что на перегретом телефоне
 * кадр стоит 83 мс вместо 36 — то есть счёт без остановки в какой-то момент начинает
 * впустую жечь батарею. Поэтому в обоих режимах есть тепловой предохранитель.
 */
class IndexingPace(
    private val context: Context,
    /** Пользователь потребовал всегда тихую индексацию. */
    private val alwaysQuiet: Boolean,
    /** Проход запущен по ночному расписанию (зарядка + простой), темп можно не выяснять. */
    private val forceFull: Boolean,
    private val isStopped: () -> Boolean,
) {

    enum class Mode(val threads: Int) {
        /** Всё, что есть: ночной проход по всей медиатеке. */
        FULL(FULL_THREADS),

        /** Незаметно: новые дневные снимки. */
        QUIET(QUIET_THREADS),
    }

    private val power = context.getSystemService(PowerManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)

    private val runStartedAt = SystemClock.elapsedRealtime()
    private var modeCheckedAt = 0L
    private var cachedMode = if (forceFull) Mode.FULL else Mode.QUIET
    private var workedMs = 0L
    private var sliceStartedAt = SystemClock.elapsedRealtime()
    private var headroomAt = 0L
    private var headroom = Float.NaN

    val mode: Mode
        get() {
            val now = SystemClock.elapsedRealtime()
            if (now - modeCheckedAt < MODE_TTL_MS) return cachedMode
            modeCheckedAt = now
            cachedMode = detect()
            return cachedMode
        }

    private fun detect(): Mode {
        if (alwaysQuiet) return Mode.QUIET
        if (forceFull) return Mode.FULL
        // Приложение на экране: пользователь видит прогресс и ждёт результата.
        if (SpaceGalleryApp.isOnScreen) return Mode.FULL
        val charging = battery?.isCharging == true
        val idle = power?.isDeviceIdleMode == true
        return if (charging && idle) Mode.FULL else Mode.QUIET
    }

    /** Для журнала: почему выбран такой темп. */
    fun describe(): String = "%s (%d потока, экран=%b зарядка=%b простой=%b запас=%.2f)".format(
        mode, mode.threads, SpaceGalleryApp.isOnScreen,
        battery?.isCharging == true, power?.isDeviceIdleMode == true, headroom(),
    )

    /**
     * Тихий проход не длится вечно: отработав своё окно, он уходит, а остаток медиатеки
     * ждёт ночи. Иначе дневная индексация за несколько часов съедает суточный лимит
     * foreground-сервиса — и ночью его уже не будет.
     */
    fun exhausted(): Boolean =
        mode == Mode.QUIET && SystemClock.elapsedRealtime() - runStartedAt > QUIET_BUDGET_MS

    /**
     * Вызывается после каждого обработанного кадра. В тихом режиме держит скважность,
     * в любом — ждёт, пока телефон не остынет.
     */
    suspend fun tick() {
        val now = SystemClock.elapsedRealtime()
        workedMs += now - sliceStartedAt
        if (mode == Mode.QUIET && workedMs >= SLICE_MS) {
            delay((workedMs * (QUIET_DUTY_DIVISOR - 1)).coerceAtMost(MAX_PAUSE_MS))
            workedMs = 0
        }
        coolDown()
        sliceStartedAt = SystemClock.elapsedRealtime()
    }

    /** Ждёт, пока тепловой запас не вернётся к [COOL_HEADROOM]. */
    private suspend fun coolDown() {
        if (headroom().let { it.isNaN() || it < HOT_HEADROOM }) return
        Log.i(TAG, "Телефон перегрелся (запас %.2f) — ждём".format(headroom))
        val start = SystemClock.elapsedRealtime()
        while (!isStopped() && SystemClock.elapsedRealtime() - start < MAX_COOLDOWN_MS) {
            delay(COOLDOWN_STEP_MS)
            val value = headroom()
            if (value.isNaN() || value <= COOL_HEADROOM) break
        }
        workedMs = 0
    }

    /** `getThermalHeadroom` возвращает NaN, если звать его чаще раза в секунду. */
    private fun headroom(): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Float.NaN
        val now = SystemClock.elapsedRealtime()
        if (now - headroomAt < HEADROOM_TTL_MS) return headroom
        headroomAt = now
        headroom = runCatching { power?.getThermalHeadroom(0) ?: Float.NaN }.getOrDefault(Float.NaN)
        return headroom
    }

    private companion object {
        const val TAG = "IndexingPace"

        /** Замеры: 4 потока — оптимум, 8 хуже из-за малых ядер (docs/performance.md). */
        const val FULL_THREADS = 4
        const val QUIET_THREADS = 2

        /** Работаем такими кусочками, между ними пауза. */
        const val SLICE_MS = 400L

        /** Пауза втрое длиннее работы — занимаем около четверти времени. */
        const val QUIET_DUTY_DIVISOR = 4
        const val MAX_PAUSE_MS = 3_000L
        const val QUIET_BUDGET_MS = 10 * 60 * 1000L
        const val MODE_TTL_MS = 2_000L
        const val HEADROOM_TTL_MS = 1_100L

        /** Выше этого запаса система уже снижает частоты — считать дальше невыгодно. */
        const val HOT_HEADROOM = 0.85f
        const val COOL_HEADROOM = 0.7f
        const val COOLDOWN_STEP_MS = 5_000L
        const val MAX_COOLDOWN_MS = 2 * 60 * 1000L
    }
}
