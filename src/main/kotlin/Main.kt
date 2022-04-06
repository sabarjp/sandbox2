@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
import kotlinx.coroutines.*
import kotlinx.coroutines.internal.*;
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue

private val logScope = CoroutineScope(Dispatchers.Default)
private val reportsDispatcher = Dispatchers.IO.limitedParallelism(4)
private val apiDispatcher = Dispatchers.IO.limitedParallelism(16)
private val reportsScope = CoroutineScope(reportsDispatcher + SupervisorJob())
private val apiScope = CoroutineScope(apiDispatcher + SupervisorJob())

// THIS SIMULATES JUST USING DISPATCHER.IO
private val daoDispatcher = Dispatchers.IO.limitedParallelism(64)
private val daoScope = CoroutineScope(daoDispatcher)

// a given "report" will have a certain amount of api calls underneath it, to simulate
// different reports having different execution times.
private val reportsData = arrayOf(
    5, 5, 5, 5, 5, 10, 4, 22, 4, 1, 344, 2, 5, 6, 8, 12, 30, 1, 222, 4902, 2, 4, 19, 2, 44, 2, 1, 7, 13, 2, 4, 6, 421, 3, 5, 6, 7,9, 35, 66, 2, 3, 4, 9, 34, 12, 6, 84, 19, 33
);


suspend fun main() {
    val start = System.currentTimeMillis()

    val jobs: List<Int> = (reportsData.indices).map {
        createReportRequest(it)
    }

    monitor(start);

    runBlocking {
        delay(60000L)
        println("Stopping after one minute.")
    }
}

suspend fun monitor(start: Long) = logScope.launch {
    println("Begin monitor at ${System.currentTimeMillis() - start}")

    while (true) {
        val ping = System.currentTimeMillis()

        delay(100);


        val queue1 = (reportsDispatcher.asLimitedDispatcher()).queue();
        val queue2 = (daoDispatcher.asLimitedDispatcher()).queue();
        val queue3 = (apiDispatcher.asLimitedDispatcher()).queue();
        val workers1 = (reportsDispatcher.asLimitedDispatcher()).runningWorkers();
        val workers2 = (daoDispatcher.asLimitedDispatcher()).runningWorkers();
        val workers3 = (apiDispatcher.asLimitedDispatcher()).runningWorkers();

        // NOTE: the timing here is to see if our thread is being delayed in the timing
        print("${System.currentTimeMillis() - ping} #### reports: ${workers1} / ${queue1?.size}     dao: ${workers2} / ${queue2?.size}    apis: ${workers3} / ${queue3?.size}\r")
    }
}

//region horrible reflection
@Suppress("INVISIBLE_REFERENCE", "EXPOSED_FUNCTION_RETURN_TYPE")
fun CoroutineDispatcher.asLimitedDispatcher(): LimitedDispatcher =
    this as LimitedDispatcher

@Suppress("INVISIBLE_REFERENCE", "EXPOSED_RECEIVER_TYPE", "EXPOSED_FUNCTION_RETURN_TYPE")
fun LimitedDispatcher.queue(): LockFreeTaskQueue<Runnable> =
    this.declaredFieldValue("queue")

@Suppress("INVISIBLE_REFERENCE", "EXPOSED_RECEIVER_TYPE", "EXPOSED_FUNCTION_RETURN_TYPE")
fun LimitedDispatcher.runningWorkers(): Int =
    this.declaredFieldValue("runningWorkers")

@Suppress("INVISIBLE_REFERENCE", "EXPOSED_RECEIVER_TYPE")
inline fun <reified A> LimitedDispatcher.declaredFieldValue(name: String): A =
    declaredField(name).get(this) as A

@Suppress("INVISIBLE_REFERENCE", "EXPOSED_RECEIVER_TYPE")
fun LimitedDispatcher.declaredField(name: String): java.lang.reflect.Field =
    this::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }
//endregion


suspend fun createReportRequest(num: Int) : Int {
    // coroutine kicks off first
    startReportsLookupProcess(num)

    // something async happens here
    fakeAsyncWork()

    // return something
    return num;
}

suspend fun fakeAsyncWork() {
    delay(0)
}

@OptIn(ExperimentalTime::class)
fun startReportsLookupProcess(num: Int) = reportsScope.launch {
    runCatching {
        //println("Kicked off $num")

        val (_, time) = measureTimedValue {
            buildReport(num)
        }
        println("Done run $num | loaded ${reportsData[num]} after $time")
    }.onFailure {
        println(it.message)
        throw it
    }
}

suspend fun buildReport(num: Int): List<Int> {
    // NOTE: kotlin map does **not** run in parallel; each item is done
    // sequentially.
    val rand = 2 + (Random.nextInt(16))

    return (1..rand).map {
        //println("building report for $num ->> $it")

        // simulate results depending on each other
        val a = apiCall1()
        val b = apiCall2(a, num)

        b
    }
}

// simple call, just does one thing and returns
suspend fun apiCall1(param: Int = 0): Int = withContext(apiDispatcher) {
    // our retrofit uses the synchronous execute function, not enqueue, so we
    // can't do any async action here in the simulation.
    val start = System.currentTimeMillis();
    while(System.currentTimeMillis() < start + 300L) {
        // busy wait
        Thread.sleep(50)
    }
    
    return@withContext 2
}

// more complex call, it does async crazy stuff and takes the bulk of the time
// simulating all the calls for build ids and test cases
suspend fun apiCall2(param: Int = 0, num: Int): Int = withContext(apiDispatcher) {
    val thingsToDo = reportsData[num]

    (1..thingsToDo).map {
        // NOTE: there is no monitoring on the DAO scope
        // since it is not a limitedDispatcher and we can't examine it!
        daoScope.async {
            apiCall3()
        }
    }

    return@withContext 2
}

suspend fun apiCall3(param: Int = 0): Int = withContext(apiDispatcher) {
    // our retrofit uses the synchronous execute function, not enqueue, so we
    // can't do any async action here in the simulation.
    val start = System.currentTimeMillis();
    while(System.currentTimeMillis() < start + 300L) {
        // busy wait
        Thread.sleep(50)
    }

    return@withContext 2
}