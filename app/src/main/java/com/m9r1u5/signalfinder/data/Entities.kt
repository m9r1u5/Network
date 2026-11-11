package com.m9r1u5.signalfinder.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A named place where the signal was measured for 10 seconds. */
@Entity(tableName = "spots")
data class Spot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val avgDbm: Int?,
    val minDbm: Int?,
    val maxDbm: Int?,
    val score: Int,
    val bars: Int,
    val tech: String,
    val networkType: String,
    val carrier: String,
    val cellId: String,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val wifiRssi: Int?,
    val lat: Double?,
    val lon: Double?,
    val accuracyM: Float?,
    val simLabel: String,
    val samples: Int,
    val timestamp: Long,
    val downMbps: Double? = null,
    val upMbps: Double? = null,
    val pingMs: Int? = null,
    val speedTimestamp: Long? = null,
)

/** One logged reading taken while walking. */
@Entity(tableName = "readings", indices = [Index("timestamp")])
data class Reading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val dbm: Int?,
    val score: Int,
    val bars: Int,
    val tech: String,
    val networkType: String,
    val carrier: String,
    val cellId: String,
    val lat: Double?,
    val lon: Double?,
    val accuracyM: Float?,
    val simLabel: String,
)
