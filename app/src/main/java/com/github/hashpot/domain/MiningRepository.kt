package com.github.hashpot.domain

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.hashpot.data.BlockInfo
import com.github.hashpot.data.BlockTemplate
import com.github.hashpot.data.MempoolBlock
import com.github.hashpot.data.MempoolTransaction
import com.github.hashpot.data.MiningConfig
import com.github.hashpot.data.MiningStats
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class MiningRepository(
    private val dataStore: DataStore<androidx.datastore.preferences.core.Preferences>
) {

    // Preference keys
    private object PreferencesKeys {
        val THREADS = intPreferencesKey("threads")
        val BTC_ADDRESS = stringPreferencesKey("btc_address")
    }

    companion object {
        const val TAG = "MiningRepository"
        const val BLOCK_REWARD_HALVING_INTERVAL = 210000
        const val FALLBACK_BTC_ADDRESS = "bc1qn5n9shs0q6d0l9k60rfy27xkj07wmf0ltkccqv"
        const val INITIAL_BLOCK_REWARD = 50 * 100_000_000L // 50 BTC in satoshis
        const val MAX_BLOCK_SIZE_BYTES = 1_000_000 // Simplified block size limit

        const val BLOCK_TEMPLATE_REFRESH_PERIOD_MS = 30000L // 30 seconds
        const val STATS_UPDATE_INTERVAL = 1000L // 1 second
    }

    // Mining statistics
    private val _miningStats = MutableStateFlow(MiningStats())
    val miningStats: StateFlow<MiningStats> = _miningStats.asStateFlow()

    // Mining configuration
    val miningConfig: Flow<MiningConfig> = dataStore.data.map { preferences ->
        MiningConfig(
            threads = preferences[PreferencesKeys.THREADS] ?: 1,
            bitcoinAddress = preferences[PreferencesKeys.BTC_ADDRESS].orEmpty()
        )
    }

    // Coroutine scope for mining
    private val miningScope = CoroutineScope(Dispatchers.Default)
    private val isRunning = AtomicBoolean(false)
    private val md = MessageDigest.getInstance("SHA-256")

    // Job control
    private var blockTemplateRefreshJob: Job? = null
    private val miningJobs = mutableListOf<Job>()

    // Shared nonce counter across threads
    private val globalNonce = AtomicInteger(0)

    // Current mining context data
    private var currentBlockTemplate: BlockTemplate? = null
    private var currentTransactions: List<MempoolTransaction> = emptyList()

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
                isLenient = true
            })
        }
    }

    // Save mining configuration
    suspend fun saveMiningConfig(config: MiningConfig) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.THREADS] = config.threads
            preferences[PreferencesKeys.BTC_ADDRESS] = config.bitcoinAddress
        }
        Log.d(TAG, "saveMiningConfig: $config")

        // If mining is running, restart with new configuration
        if (isRunning.get()) {
            stopMining()
            delay(1.seconds)
            startMining()
        }
    }

    // Start mining
    fun startMining() {
        Log.d(TAG, "startMining")
        if (isRunning.getAndSet(true)) return

        val startTime = System.currentTimeMillis()

        _miningStats.value = MiningStats(
            isRunning = true,
            startTime = startTime,
            targetDifficulty = _miningStats.value.targetDifficulty
        )

        // Start a periodic job to refresh block template
        blockTemplateRefreshJob = miningScope.launch {
            while (isRunning.get()) {
                try {
                    updateBlockTemplate()
                    delay(BLOCK_TEMPLATE_REFRESH_PERIOD_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error refreshing block template: ${e.message}", e)
                    delay(5000) // Wait before retrying
                }
            }
        }
    }

    // Stop mining
    fun stopMining() {
        Log.d(TAG, "stopMining")
        isRunning.set(false)

        // Cancel all mining jobs
        blockTemplateRefreshJob?.cancel()
        miningJobs.forEach { it.cancel() }
        miningJobs.clear()

        _miningStats.value = _miningStats.value.copy(isRunning = false)
    }

    private suspend fun updateBlockTemplate() {
        try {
            val threads = miningConfig.first().threads
            Log.d(TAG, "updateBlockTemplate: refreshing with $threads threads")

            // Cancel existing mining jobs
            miningJobs.forEach { it.cancel() }
            miningJobs.clear()

            // Reset the global nonce for the new block
            globalNonce.set(0)

            // Fetch new block data
            val blockTemplate = fetchLatestBlockTemplate()
            val mempoolTransactions = fetchMempoolTransactions()

            val selectedTransactions = selectTransactionsForBlock(mempoolTransactions)
            val totalFees = selectedTransactions.sumOf { it.fee ?: 0 }

            val coinbaseTx = createCoinbaseTransaction(
                blockHeight = blockTemplate.height,
                btcAddress = miningConfig.first().bitcoinAddress.ifBlank { FALLBACK_BTC_ADDRESS },
                fees = totalFees
            )

            // Combine all transactions (coinbase first)
            val allTransactions = listOf(coinbaseTx) + selectedTransactions

            // Build Merkle root
            val merkleRoot = buildMerkleTree(allTransactions)

            // Update block template with new merkle root
            val updatedTemplate = blockTemplate.copy(merkleRoot = merkleRoot)

            // Store current mining context
            currentBlockTemplate = updatedTemplate
            currentTransactions = allTransactions

            _miningStats.value = _miningStats.value.copy(
                currentBlock = updatedTemplate,
                transactionsInBlock = allTransactions.size,
                totalFees = totalFees
            )

            // Start mining on multiple threads
            miningJobs.clear()
            repeat(threads) { threadIndex ->
                val job = miningScope.launch {
                    mine(updatedTemplate, allTransactions, threadIndex)
                }
                miningJobs.add(job)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in mining setup: ${e.message}", e)
        }
    }

    private suspend fun fetchMempoolTransactions(): List<MempoolTransaction> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching mempool transactions")
            val txIds: List<String> = client.get("https://mempool.space/api/mempool/txids").body()

            // Limit to top 100 transactions for simplicity
            val limitedTxIds = txIds.take(100)

            // Fetch details for each transaction
            limitedTxIds.mapNotNull { txId ->
                try {
                    client.get("https://mempool.space/api/tx/$txId").body<MempoolTransaction>()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch transaction $txId: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch mempool transactions: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchLatestBlockTemplate(): BlockTemplate = withContext(Dispatchers.IO) {
        Log.d(TAG, "fetchLatestBlockTemplate")
        // Get recent blocks from mempool.space API
        val blocksResponse: List<MempoolBlock> = client.get("https://mempool.space/api/v1/blocks").body()

        if (blocksResponse.isEmpty()) {
            throw Exception("No blocks returned from API")
        }

        // Get the latest block details
        val latestBlock = blocksResponse.first()
        val blockInfo: BlockInfo = client.get("https://mempool.space/api/block/${latestBlock.id}").body()

        // Create a block template from the latest block
        return@withContext BlockTemplate(
            version = blockInfo.version.toInt(),
            previousBlockHash = blockInfo.previousblockhash,
            merkleRoot = blockInfo.merkle_root,
            timestamp = blockInfo.timestamp,
            bits = blockInfo.bits.toString(16), // Convert to hex string
            height = blockInfo.height.toInt(),
            difficulty = latestBlock.difficulty
        )
    }

    private fun selectTransactionsForBlock(transactions: List<MempoolTransaction>): List<MempoolTransaction> {
        // Sort by fee rate (higher first)
        val sorted = transactions.sortedByDescending { it.fee?.toDouble()?.div((it.size ?: 1)) }

        // Select transactions until we reach block size limit
        var currentSize = 0
        val selected = mutableListOf<MempoolTransaction>()

        for (tx in sorted) {
            if (currentSize + (tx.size ?: 0) <= MAX_BLOCK_SIZE_BYTES) {
                selected.add(tx)
                currentSize += tx.size ?: 0
            } else {
                break
            }
        }

        return selected
    }

    private fun createCoinbaseTransaction(blockHeight: Int, btcAddress: String, fees: Long): MempoolTransaction {
        // Calculate block reward (halving every 210,000 blocks)
        val halvings = blockHeight / BLOCK_REWARD_HALVING_INTERVAL
        var blockReward = INITIAL_BLOCK_REWARD
        repeat(halvings) { blockReward /= 2 }

        val totalReward = blockReward + fees

        // Create a simplified coinbase transaction
        return MempoolTransaction(
            txid = "coinbase_${System.currentTimeMillis()}",
            fee = 0,
            size = 100, // Approximate size for coinbase tx
            inputs = emptyList(), // Coinbase has no inputs
            outputs = listOf(
                MempoolTransaction.Output(
                    value = totalReward,
                    scriptPubKey = createP2wpkhScript(btcAddress)
                )
            )
        )
    }

    private fun createP2wpkhScript(address: String): String {
        // Placeholder for P2WPKH script
        return "0014${"a".repeat(40)}"
    }

    // Build Merkle tree from transactions
    private fun buildMerkleTree(transactions: List<MempoolTransaction>): String {
        if (transactions.isEmpty()) return "0".repeat(64)

        // Start with transaction hashes
        var hashes = transactions.map { it.txid }

        // If odd number, duplicate last hash
        if (hashes.size % 2 != 0) {
            hashes = hashes + hashes.last()
        }

        // Build tree layers
        while (hashes.size > 1) {
            val newLevel = mutableListOf<String>()
            for (i in hashes.indices step 2) {
                val combined = hashes[i] + hashes[i + 1]
                val hash = sha256Twice(hexStringToByteArray(combined)).joinToString("") { "%02x".format(it) }
                newLevel.add(hash)
            }
            hashes = newLevel
            // If odd number, duplicate last hash
            if (hashes.size % 2 != 0 && hashes.size > 1) {
                hashes = hashes + hashes.last()
            }
        }

        return hashes.first()
    }

    private suspend fun mine(blockTemplate: BlockTemplate, transactions: List<MempoolTransaction>, threadId: Int) {
        var hashCount = 0L
        var attemptCount = 0L
        var bestMatch = 0
        var lastStatsUpdate = System.currentTimeMillis()

        val startTime = System.currentTimeMillis()
        val targetDifficulty = _miningStats.value.targetDifficulty
        val target = BigInteger.ONE.shiftLeft(256 - targetDifficulty.toInt())

        Log.d(TAG, "Thread $threadId started mining")

        while (isRunning.get()) {
            // Get a unique nonce for this attempt from the global counter
            val nonce = globalNonce.getAndIncrement()

            // Create a block header with the current nonce
            val blockHeader = createBlockHeader(blockTemplate, nonce)

            // Double SHA-256 hash (Bitcoin standard)
            val hash = withContext(Dispatchers.Default) {
                sha256Twice(blockHeader)
            }

            // Convert hash to BigInteger for difficulty comparison
            val hashInt = BigInteger(1, hash)

            // Check if hash meets target difficulty
            if (hashInt < target) {
                val hashHex = hash.joinToString("") { String.format("%02x", it) }
                Log.i(TAG, "Block found by thread $threadId! Nonce: $nonce, Hash: $hashHex")

                // Update stats
                _miningStats.value = _miningStats.value.copy(
                    blocksFound = _miningStats.value.blocksFound + 1,
                    lastBlockHash = hashHex
                )

                // Broadcast the block
                broadcastBlock(blockTemplate.copy(nonce = nonce), transactions)

                // Let the template refresh job handle getting a new block
                return
            }

            // Count leading zeros for statistics
            val leadingZeros = countLeadingZeros(hash)
            if (leadingZeros > bestMatch) {
                bestMatch = leadingZeros
            }

            hashCount++
            attemptCount++

            // Update statistics periodically
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastStatsUpdate >= STATS_UPDATE_INTERVAL) {
                val elapsedTimeSeconds = (currentTime - startTime) / 1000.0
                val hashRate = hashCount / elapsedTimeSeconds

                // Update stats atomically
                _miningStats.value = _miningStats.value.copy(
                    hashRate = _miningStats.value.hashRate + (hashRate - _miningStats.value.hashRate) / threads, // Moving average
                    totalHashes = _miningStats.value.totalHashes + hashCount,
                    attemptsCount = _miningStats.value.attemptsCount + attemptCount,
                    bestMatchBits = maxOf(_miningStats.value.bestMatchBits, bestMatch)
                )

                hashCount = 0
                attemptCount = 0
                lastStatsUpdate = currentTime
            }

            // Give other coroutines a chance to run
            if (hashCount % 10000 == 0L) {
                yield()
            }
        }
    }

    // Thread-safe property calculation
    private val threads: Int
        get() = miningJobs.size

    // Conceptual block broadcasting
    private fun broadcastBlock(blockTemplate: BlockTemplate, transactions: List<MempoolTransaction>) {
        Log.i(TAG, "Conceptual block broadcast:")
        Log.i(TAG, "Block hash: ${blockTemplate.previousBlockHash}")
        Log.i(TAG, "Transactions: ${transactions.size}")
        Log.i(TAG, "Would now send this block to Bitcoin network peers")
    }

    private fun createBlockHeader(template: BlockTemplate, nonce: Int): ByteArray {
        // Bitcoin block header structure:
        // - Version (4 bytes, little endian)
        // - Previous block hash (32 bytes, little endian)
        // - Merkle root (32 bytes, little endian)
        // - Timestamp (4 bytes, little endian)
        // - Bits/Target (4 bytes, little endian)
        // - Nonce (4 bytes, little endian)

        val buffer = ByteArray(80) // Bitcoin block header is always 80 bytes

        // Version (4 bytes, little endian)
        writeInt32LE(buffer, 0, template.version)

        // Previous block hash (32 bytes, byte-reversed)
        val prevHash = hexStringToByteArray(template.previousBlockHash)
        System.arraycopy(reverseBytes(prevHash), 0, buffer, 4, 32)

        // Merkle root (32 bytes, byte-reversed)
        val merkleRoot = hexStringToByteArray(template.merkleRoot)
        System.arraycopy(reverseBytes(merkleRoot), 0, buffer, 36, 32)

        // Timestamp (4 bytes, little endian)
        writeInt32LE(buffer, 68, template.timestamp)

        // Bits/Target (4 bytes, little endian)
        val bits = if (template.bits.startsWith("0x")) {
            Integer.parseInt(template.bits.substring(2), 16)
        } else if (template.bits.all { it.isDigit() }) {
            template.bits.toInt()
        } else {
            Integer.parseInt(template.bits, 16)
        }
        writeInt32LE(buffer, 72, bits)

        // Nonce (4 bytes, little endian)
        writeInt32LE(buffer, 76, nonce)

        return buffer
    }

    // Double SHA-256 hash (Bitcoin standard)
    private fun sha256Twice(data: ByteArray): ByteArray {
        val firstHash = md.digest(data)
        return md.digest(firstHash)
    }

    // Count leading zeros in hash (similar to Bitcoin difficulty)
    private fun countLeadingZeros(hash: ByteArray): Int {
        var leadingZeros = 0
        for (byte in hash) {
            if (byte == 0.toByte()) {
                leadingZeros += 8
            } else {
                val zeros = Integer.numberOfLeadingZeros(byte.toInt() and 0xFF) - 24
                leadingZeros += zeros
                break
            }
        }
        return leadingZeros
    }

    // Helper functions for Bitcoin block header construction
    private fun writeInt32LE(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun hexStringToByteArray(hexString: String): ByteArray {
        val cleanHex = hexString.replace("0x", "").replace(" ", "")
        val len = cleanHex.length
        val data = ByteArray(len / 2)

        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) +
                    Character.digit(cleanHex[i + 1], 16)).toByte()
            i += 2
        }

        return data
    }

    private fun reverseBytes(data: ByteArray): ByteArray {
        val reversed = data.copyOf()
        for (i in 0 until data.size / 2) {
            val temp = reversed[i]
            reversed[i] = reversed[data.size - i - 1]
            reversed[data.size - i - 1] = temp
        }
        return reversed
    }

    // Calculate Bitcoin difficulty from bits
    private fun bitsToTarget(bits: String): BigInteger {
        val bitsValue = Integer.parseInt(bits, 16)
        val exponent = bitsValue shr 24
        val mantissa = bitsValue and 0x007FFFFF

        var target = BigInteger.valueOf(mantissa.toLong())

        // Apply the exponent (shift left by 8 * (exponent - 3))
        if (exponent > 3) {
            target = target.shiftLeft(8 * (exponent - 3))
        } else {
            target = target.shiftRight(8 * (3 - exponent))
        }

        return target
    }
}