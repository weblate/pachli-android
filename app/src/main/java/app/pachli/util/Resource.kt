package app.pachli.util

sealed class Resource<T>(open val data: T?)

class Loading<T> (override val data: T? = null) : Resource<T>(data)

class Success<T> (override val data: T? = null) : Resource<T>(data)

class Error<T>(
    override val data: T? = null,
    val errorMessage: String? = null,
    val cause: Throwable? = null,
) : Resource<T>(data)
