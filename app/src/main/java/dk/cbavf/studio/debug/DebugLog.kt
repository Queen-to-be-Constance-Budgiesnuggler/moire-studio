package dk.cbavf.studio.debug

import kotlin.math.abs
import kotlin.math.min

class DebugLog(var level: Int = 0, private val capacity: Int = 2000,
               private val clock: () -> Long = { System.currentTimeMillis() }) {
    data class Entry(val timeMs: Long, val level: Int, val tag: String, val msg: String)
    
    @Volatile
    private var buffer = Array<Entry?>(capacity) { null }
    @Volatile
    private var head = 0
    @Volatile
    private var tail = 0
    @Volatile
    private var count = 0
    
    val size: Int
        get() = count
    
    @Synchronized
    fun log(level: Int, tag: String, msg: String) {
        if (level < 1 || level > this.level) return
        
        val entry = Entry(clock(), level, tag, msg)
        
        if (count == capacity) {
            head = (head + 1) % capacity
            count--
        }
        
        buffer[tail] = entry
        tail = (tail + 1) % capacity
        count++
    }
    
    @Synchronized
    fun entries(): List<Entry> {
        val result = mutableListOf<Entry>()
        var index = head
        for (i in 0 until count) {
            result.add(buffer[index]!!)
            index = (index + 1) % capacity
        }
        return result
    }
    
    @Synchronized
    fun lines(): List<String> {
        val result = mutableListOf<String>()
        var index = head
        for (i in 0 until count) {
            val entry = buffer[index]!!
            val time = entry.timeMs
            val hours = (time / 3600000) % 24
            val minutes = (time / 60000) % 60
            val seconds = (time / 1000) % 60
            val milliseconds = time % 1000
            
            val levelChar = when (entry.level) {
                ERROR -> 'E'
                INFO -> 'I'
                TRACE -> 'T'
                else -> ' '
            }
            
            result.add(String.format(java.util.Locale.US, "%02d:%02d:%02d.%03d %c/%s: %s",
                hours, minutes, seconds, milliseconds,
                levelChar, entry.tag, entry.msg))
            index = (index + 1) % capacity
        }
        return result
    }
    
    @Synchronized
    fun clear() {
        head = 0
        tail = 0
        count = 0
    }
    
    companion object {
        const val OFF = 0
        const val ERROR = 1
        const val INFO = 2
        const val TRACE = 3
    }
}
