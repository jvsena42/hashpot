package com.github.hashpot.data

data class MiningStats(
    val hashRate: Double = 0.0,
    val totalHashes: Long = 0L,
    val attemptsCount: Long = 0L,
    val bestMatchBits: Int = 0,
    val isRunning: Boolean = false,
    val startTime: Long = 0L,
    val targetDifficulty: Double = 119116256505723.5,
    val currentBlock: BlockTemplate? = null,
    val transactionsInBlock: Int = 0,
    val blocksFound: Int = 0,
    val totalFees: Long = 0,
    val lastBlockHash: String = "",
)