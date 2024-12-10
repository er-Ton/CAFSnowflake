import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * CAFSnowflake: A Snowflake ID generator that can automatically fix clock issues.
 *
 * To address clock rollback scenarios, CAFSnowflake maintains a clock offset,
 * which is usually set to 0.
 * When a clock rollback occurs on the server, CAFSnowflake records the clock
 * offset and uses it to adjust the timestamp when generating IDs.
 * During subsequent operations, CAFSnowflake will continuously attempt to
 * compensate for the clock offset until it returns to 0.
 *
 * This feature ensures that IDs can still be generated normally even when a
 * clock rollback happens.
 *
 * For a period after a clock rollback, the timestamps used by the Snowflake
 * algorithm may differ slightly from the server's actual timestamps
 * (the timestamps in the generated IDs may be slightly larger than the
 * server's timestamps), which could impact the trend incrementality.
 * However, considering that scenarios involving clock rollback generally
 * have less stringent requirements for trend incrementality, this strategy
 * is entirely feasible.
 *
 * -----------------------------------------------------------------------------
 * CAFSnowflake：一个可以自动修复时钟问题的雪花算法ID生成器。
 * 针对时钟回退现象，CAFSnowflake维护一个时钟偏移量，通常情况下，时钟偏移量等于0。
 * 当服务器发生了时钟回退现象，CAFSnowflake会记录下时钟偏移量，用以修正生成id时使用的时间戳。
 * 在之后的运行过程中，CAFSnowflake会不断尝试补偿该时钟偏移量，直至其等于0。
 *
 * 这一特性保证即使服务器发生了时钟回退，也可以正常生成ID。
 *
 * 发生时钟回退后的一段时间内，雪花算法采用的时间戳与服务器的实际时间戳
 * 会有一些差异（生成的ID中时间戳会略大于服务器时间戳），可能对趋势自增性造成一定影响。
 * 但考虑到在可能发生时钟回退的场景下，对趋势自增性的要求通常不会太严格，因此这一策略是完全可行的。
 *
 * @author erton
 * @date 2024/12/10
 */
public class CAFSnowflake {

    private final static long START_TIMESTAMP = 1577808000000L;
    private final static long WORKER_ID_BITS = 8L;
    private final static long SEQUENCE_BITS = 12L;

    private final static long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private final static long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);

    private final static long WORKER_ID_OFFSET = SEQUENCE_BITS;
    private final static long TIMESTAMP_OFFSET = WORKER_ID_OFFSET + WORKER_ID_BITS;

    private long workID;
    private long sequence = 0l;
    private long lastStmp = -1l;
    //时钟偏移量
    private long stmpOffset = 0l;

    public CAFSnowflake(long workID) {
        if(workID > MAX_WORKER_ID) {
            throw new IllegalArgumentException("CAFSnowflake: The workerID exceeded the upper limit.");
        }
        this.workID = workID;
    }

    public synchronized long nextId() {
        long now = now();
        long virtualStmp = now + stmpOffset;
        if(virtualStmp < lastStmp) {
            virtualStmp = doOffset(now);
        } else if(stmpOffset > 0){
            virtualStmp = correctOffset(now);
        }

        sequence = (virtualStmp == lastStmp) ? sequence + 1 : 0;
        if(sequence > MAX_SEQUENCE) {
            if(stmpOffset > 0) {
                virtualStmp++;
                stmpOffset++;
            } else {
                //不存在偏移量时，虚拟时间戳==实际时间戳，直接获取下一时刻
                virtualStmp = waitNextMill();
            }
            sequence = 0l;
        }
        lastStmp = virtualStmp;
        return (virtualStmp - START_TIMESTAMP) << TIMESTAMP_OFFSET
                | workID << WORKER_ID_OFFSET
                | sequence;

    }

    private long now() {
        return System.currentTimeMillis();
    }

    private long waitNextMill() {
        long mill = now();
        while (mill <= lastStmp) {
            mill = now();
        }
        return mill;
    }

    /**
     * 补偿时钟偏移量
     * @param now   当前真实的服务器时间戳
     * @return      修正后的虚拟时间戳
     */
    private long correctOffset(long now) {
        long con = Math.min(now - lastStmp + stmpOffset, stmpOffset);
        if(con > 0) {
            stmpOffset -= con;
        }
        return now + stmpOffset;
    }

    /**
     * 增加时钟偏移量，以保证正常生产id
     * @param now   当前真实的服务器时间戳
     * @return      修正后的虚拟时间戳
     */
    private long doOffset(long now) {
        long don = lastStmp - now;
        if(don > 0) {
            stmpOffset = don;
        }
        return now + stmpOffset;
    }

}
