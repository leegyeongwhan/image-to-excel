package com.imagetoexcel.dto

data class OrderData(
    val name: String,
    val address: String,
    val phone: String? = null
)
