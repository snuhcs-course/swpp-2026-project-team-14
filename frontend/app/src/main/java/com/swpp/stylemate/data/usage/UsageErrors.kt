package com.swpp.stylemate.data.usage

import com.swpp.stylemate.data.AuthException
import com.swpp.stylemate.data.recommendation.OutfitApiException
import com.swpp.stylemate.data.tryon.FittingApiException
import java.io.IOException
import java.io.InterruptedIOException

internal fun usageErrorCode(error: Exception): String = when (error) {
    is AuthException -> error.code
    is OutfitApiException -> error.code
    is FittingApiException -> error.code
    is InterruptedIOException -> "TIMEOUT"
    is IOException -> "NETWORK"
    is IllegalArgumentException -> "INVALID_INPUT"
    else -> "UNEXPECTED_ERROR"
}
