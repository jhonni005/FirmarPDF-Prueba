package com.zonadev.firmarpdf.data.signature

data class SignaturePoint(val x: Float, val y: Float)
data class SignatureStroke(val color: Int, val width: Float, val points: List<SignaturePoint>)

