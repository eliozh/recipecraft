package com.elio.recipecraft.di

import com.elio.recipecraft.data.llm.DefaultLlmClientFactory
import com.elio.recipecraft.data.llm.LlmClientFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkModule {

    @Binds
    abstract fun bindLlmClientFactory(impl: DefaultLlmClientFactory): LlmClientFactory

    companion object {
        // No logging interceptor: headers carry the API key and bodies carry the user's chat.
        @Provides
        @Singleton
        fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // The gap allowed between streamed chunks; a whole reply may take much longer.
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // A redirect would forward the chat body and the key header to another host, so a 3xx
            // is an error instead.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        @Provides
        @Singleton
        fun provideJson(): Json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
