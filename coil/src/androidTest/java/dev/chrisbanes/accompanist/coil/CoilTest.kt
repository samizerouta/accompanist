/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.chrisbanes.accompanist.coil

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.Text
import androidx.compose.foundation.layout.preferredSize
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.ContextAmbient
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.ui.test.assertHeightIsAtLeast
import androidx.ui.test.assertHeightIsEqualTo
import androidx.ui.test.assertIsDisplayed
import androidx.ui.test.assertPixels
import androidx.ui.test.assertWidthIsAtLeast
import androidx.ui.test.assertWidthIsEqualTo
import androidx.ui.test.captureToBitmap
import androidx.ui.test.createComposeRule
import androidx.ui.test.onNodeWithTag
import androidx.ui.test.onNodeWithText
import coil.EventListener
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.decode.Options
import coil.fetch.Fetcher
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.google.common.truth.Truth.assertThat
import dev.chrisbanes.accompanist.coil.test.R
import dev.chrisbanes.accompanist.imageloading.ImageLoadState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@LargeTest
@RunWith(JUnit4::class)
class CoilTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // Our MockWebServer. We use a response delay to simulate real-world conditions
    private val server = coilTestWebServer(responseDelayMs = 200)

    @Before
    fun setup() {
        // Start our mock web server
        server.start()
    }

    @After
    fun teardown() {
        // Shutdown our mock web server
        server.shutdown()
    }

    @Test
    fun onRequestCompleted_fromImageRequest() {
        val results = ArrayList<ImageLoadState>()
        val latch = CountDownLatch(1)

        composeTestRule.setContent {
            CoilImage(
                request = ImageRequest.Builder(ContextAmbient.current)
                    .data(resourceUri(R.raw.sample))
                    .listener { _, _ -> latch.countDown() }
                    .build(),
                modifier = Modifier.preferredSize(128.dp, 128.dp),
                onRequestCompleted = { results += it }
            )
        }

        // Wait for the Coil request listener to release the latch
        latch.await(5, TimeUnit.SECONDS)

        composeTestRule.runOnIdle {
            // And assert that we got a single successful result
            assertThat(results).hasSize(1)
            assertThat(results[0]).isInstanceOf(ImageLoadState.Success::class.java)
        }
    }

    @Test
    fun onRequestCompleted_fromBuilder() {
        val results = ArrayList<ImageLoadState>()
        val latch = CountDownLatch(1)

        composeTestRule.setContent {
            CoilImage(
                data = resourceUri(R.raw.sample),
                requestBuilder = {
                    listener { _, _ -> latch.countDown() }
                },
                modifier = Modifier.preferredSize(128.dp, 128.dp),
                onRequestCompleted = { results += it }
            )
        }

        // Wait for the Coil request listener to release the latch
        latch.await(5, TimeUnit.SECONDS)

        composeTestRule.runOnIdle {
            // And assert that we got a single successful result
            assertThat(results).hasSize(1)
            assertThat(results[0]).isInstanceOf(ImageLoadState.Success::class.java)
        }
    }

    @Test
    fun basicLoad_http() {
        val latch = CountDownLatch(1)

        composeTestRule.setContent {
            CoilImage(
                data = server.url("/image"),
                modifier = Modifier.preferredSize(128.dp, 128.dp).testTag(CoilTestTags.Image),
                onRequestCompleted = { latch.countDown() }
            )
        }

        // Wait for the onRequestCompleted to release the latch
        latch.await(5, TimeUnit.SECONDS)

        composeTestRule.onNodeWithTag(CoilTestTags.Image)
            .assertIsDisplayed()
            .assertWidthIsEqualTo(128.dp)
            .assertHeightIsEqualTo(128.dp)
    }

    @Test
    @SdkSuppress(minSdkVersion = 26) // captureToBitmap is SDK 26+
    fun basicLoad_drawable() {
        val latch = CountDownLatch(1)

        composeTestRule.setContent {
            CoilImage(
                data = resourceUri(R.drawable.red_rectangle),
                modifier = Modifier.preferredSize(128.dp, 128.dp).testTag(CoilTestTags.Image),
                onRequestCompleted = { latch.countDown() }
            )
        }

        // Wait for the onRequestCompleted to release the latch
        latch.await(5, TimeUnit.SECONDS)

        composeTestRule.onNodeWithTag(CoilTestTags.Image)
            .assertWidthIsEqualTo(128.dp)
            .assertHeightIsEqualTo(128.dp)
            .assertIsDisplayed()
            .captureToBitmap()
            .assertPixels { Color.Red }
    }

    @OptIn(ExperimentalCoilApi::class)
    @Test
    fun basicLoad_customImageLoader() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val latch = CountDownLatch(1)

        // Build a custom ImageLoader with a fake EventListener
        val eventListener = object : EventListener {
            var startCalled = 0
                private set

            override fun fetchStart(request: ImageRequest, fetcher: Fetcher<*>, options: Options) {
                startCalled++
            }
        }
        val imageLoader = ImageLoader.Builder(context)
            .eventListener(eventListener)
            .build()

        composeTestRule.setContent {
            CoilImage(
                data = resourceUri(R.drawable.red_rectangle),
                modifier = Modifier.preferredSize(128.dp, 128.dp),
                imageLoader = imageLoader,
                onRequestCompleted = { latch.countDown() }
            )
        }

        // Wait for the onRequestCompleted to release the latch
        latch.await(5, TimeUnit.SECONDS)

        // Verify that our eventListener was invoked
        assertThat(eventListener.startCalled).isAtLeast(1)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    @SdkSuppress(minSdkVersion = 26) // captureToBitmap is SDK 26+
    fun basicLoad_switchData() {
        val loadCompleteSignal = Channel<Unit>(Channel.UNLIMITED)
        val drawableResId = MutableStateFlow(R.drawable.red_rectangle)

        composeTestRule.setContent {
            val resId = drawableResId.collectAsState()
            CoilImage(
                data = resourceUri(resId.value),
                modifier = Modifier.preferredSize(128.dp, 128.dp).testTag(CoilTestTags.Image),
                onRequestCompleted = { loadCompleteSignal.offer(Unit) }
            )
        }

        // Await the first load
        runBlocking {
            withTimeout(5000) {
                loadCompleteSignal.receive()
            }
        }

        // Assert that the content is completely Red
        composeTestRule.onNodeWithTag(CoilTestTags.Image)
            .assertWidthIsEqualTo(128.dp)
            .assertHeightIsEqualTo(128.dp)
            .assertIsDisplayed()
            .captureToBitmap()
            .assertPixels { Color.Red }

        // Now switch the data URI to the blue drawable
        drawableResId.value = R.drawable.blue_rectangle

        // Await the second load
        runBlocking {
            withTimeout(5000) {
                loadCompleteSignal.receive()
            }
        }

        // Assert that the content is completely Blue
        composeTestRule.onNodeWithTag(CoilTestTags.Image)
            .assertWidthIsEqualTo(128.dp)
            .assertHeightIsEqualTo(128.dp)
            .assertIsDisplayed()
            .captureToBitmap()
            .assertPixels { Color.Blue }

        // Close the signal channel
        loadCompleteSignal.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun basicLoad_changeSize() {
        val loadCompleteSignal = Channel<Unit>(Channel.UNLIMITED)
        val sizeFlow = MutableStateFlow(128.dp)

        composeTestRule.setContent {
            val size = sizeFlow.collectAsState()
            CoilImage(
                data = resourceUri(R.drawable.red_rectangle),
                modifier = Modifier.preferredSize(size.value).testTag(CoilTestTags.Image),
                onRequestCompleted = { loadCompleteSignal.offer(Unit) }
            )
        }

        // Await the first load
        runBlocking {
            loadCompleteSignal.receive()
        }

        // Now change the size
        sizeFlow.value = 256.dp

        // Await the potential second load (which shouldn't come)
        runBlocking {
            val result = withTimeoutOrNull(3000) { loadCompleteSignal.receive() }
            assertThat(result).isNull()
        }

        // Close the signal channel
        loadCompleteSignal.close()
    }

    @Test
    fun basicLoad_nosize() {
        val latch = CountDownLatch(1)

        composeTestRule.setContent {
            CoilImage(
                data = resourceUri(R.raw.sample),
                modifier = Modifier.testTag(CoilTestTags.Image),
                onRequestCompleted = { latch.countDown() }
            )
        }

        // Wait for the onRequestCompleted to release the latch
        latch.await(5, TimeUnit.SECONDS)

        composeTestRule.onNodeWithTag(CoilTestTags.Image)
            .assertWidthIsAtLeast(1.dp)
            .assertHeightIsAtLeast(1.dp)
            .assertIsDisplayed()
    }

    @Test
    fun errorStillHasSize() {
        val latch = CountDownLatch(1)

        composeTestRule.setContent {
            CoilImage(
                data = server.url("/noimage"),
                modifier = Modifier.preferredSize(128.dp, 128.dp).testTag(CoilTestTags.Image),
                onRequestCompleted = { latch.countDown() }
            )
        }

        // Wait for the onRequestCompleted to release the latch
        latch.await(5, TimeUnit.SECONDS)

        // Assert that the layout is in the tree and has the correct size
        composeTestRule.onNodeWithTag(CoilTestTags.Image)
            .assertIsDisplayed()
            .assertWidthIsEqualTo(128.dp)
            .assertHeightIsEqualTo(128.dp)
    }

    @Test
    fun content_error() {
        val latch = CountDownLatch(1)
        val states = ArrayList<ImageLoadState>()

        composeTestRule.setContent {
            CoilImage(
                data = server.url("/noimage"),
                modifier = Modifier.preferredSize(128.dp, 128.dp),
                // Disable any caches. If the item is in the cache, the fetch is
                // synchronous which means the Loading state is skipped
                imageLoader = noCacheImageLoader(),
                onRequestCompleted = { latch.countDown() }
            ) { state ->
                states.add(state)
            }
        }

        // Wait for the onRequestCompleted to release the latch
        latch.await(5, TimeUnit.SECONDS)

        composeTestRule.runOnIdle {
            assertThat(states).hasSize(3)
            assertThat(states[0]).isEqualTo(ImageLoadState.Empty)
            assertThat(states[1]).isEqualTo(ImageLoadState.Loading)
            assertThat(states[2]).isInstanceOf(ImageLoadState.Error::class.java)
        }
    }

    @Test
    fun content_success() {
        val latch = CountDownLatch(1)
        val states = ArrayList<ImageLoadState>()

        composeTestRule.setContent {
            CoilImage(
                data = server.url("/image"),
                modifier = Modifier.preferredSize(128.dp, 128.dp),
                // Disable any caches. If the item is in the cache, the fetch is
                // synchronous which means the Loading state is skipped
                imageLoader = noCacheImageLoader(),
                onRequestCompleted = { latch.countDown() }
            ) { state ->
                states.add(state)
            }
        }

        // Wait for the onRequestCompleted to release the latch
        latch.await(5, TimeUnit.SECONDS)

        composeTestRule.runOnIdle {
            assertThat(states).hasSize(3)
            assertThat(states[0]).isEqualTo(ImageLoadState.Empty)
            assertThat(states[1]).isEqualTo(ImageLoadState.Loading)
            assertThat(states[2]).isInstanceOf(ImageLoadState.Success::class.java)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26) // captureToBitmap is SDK 26+
    fun content_custom() {
        val latch = CountDownLatch(1)

        composeTestRule.setContent {
            CoilImage(
                data = resourceUri(R.raw.sample),
                modifier = Modifier.preferredSize(128.dp, 128.dp).testTag(CoilTestTags.Image),
                onRequestCompleted = { latch.countDown() }
            ) { _ ->
                // Return an Image which just draws cyan
                Image(painter = ColorPainter(Color.Cyan))
            }
        }

        // Wait for the onRequestCompleted to release the latch
        latch.await(5, TimeUnit.SECONDS)

        // Assert that the whole layout is drawn cyan
        composeTestRule.onNodeWithTag(CoilTestTags.Image)
            .assertIsDisplayed()
            .captureToBitmap()
            .assertPixels { Color.Cyan }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun loading_slot() {
        val dispatcher = TestCoroutineDispatcher()
        val loadLatch = CountDownLatch(1)

        dispatcher.runBlockingTest {
            pauseDispatcher()

            composeTestRule.setContent {
                CoilImage(
                    request = ImageRequest.Builder(ContextAmbient.current)
                        .data(server.url("/image"))
                        .dispatcher(dispatcher)
                        .build(),
                    modifier = Modifier.preferredSize(128.dp, 128.dp),
                    // Disable memory cache. If the item is in the cache, the fetch is
                    // synchronous and the dispatcher pause has no effect
                    imageLoader = noCacheImageLoader(),
                    loading = { Text(text = "Loading") },
                    onRequestCompleted = { loadLatch.countDown() }
                )
            }

            // Assert that the loading component is displayed
            composeTestRule.onNodeWithText("Loading").assertIsDisplayed()

            // Now resume the dispatcher to start the Coil request
            dispatcher.resumeDispatcher()
        }

        // We now wait for the request to complete
        loadLatch.await(5, TimeUnit.SECONDS)

        // And assert that the loading component no longer exists
        composeTestRule.onNodeWithText("Loading").assertDoesNotExist()
    }

    @Test
    @SdkSuppress(minSdkVersion = 26) // captureToBitmap is SDK 26+
    fun error_slot() {
        val latch = CountDownLatch(1)

        composeTestRule.setContent {
            CoilImage(
                data = server.url("/noimage"),
                error = {
                    // Return failure content which just draws red
                    Image(painter = ColorPainter(Color.Red))
                },
                modifier = Modifier.preferredSize(128.dp, 128.dp).testTag(CoilTestTags.Image),
                onRequestCompleted = { latch.countDown() }
            )
        }

        // Wait for the onRequestCompleted to release the latch
        latch.await(5, TimeUnit.SECONDS)

        // Assert that the whole layout is drawn red
        composeTestRule.onNodeWithTag(CoilTestTags.Image)
            .assertIsDisplayed()
            .captureToBitmap()
            .assertPixels { Color.Red }
    }
}

/**
 * [ImageLoader] which disables all caching
 */
private fun noCacheImageLoader(): ImageLoader {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    return ImageLoader.Builder(ctx)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .diskCachePolicy(CachePolicy.DISABLED)
        .build()
}

private fun resourceUri(id: Int): Uri {
    val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
    return "${ContentResolver.SCHEME_ANDROID_RESOURCE}://$packageName/$id".toUri()
}

/**
 * [MockWebServer] which returns a valid response at the path `/image`, and a 404 for anything else.
 * We add a small delay to simulate 'real-world' network conditions.
 */
private fun coilTestWebServer(responseDelayMs: Long = 0): MockWebServer {
    val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
            "/image" -> {
                val res = InstrumentationRegistry.getInstrumentation().targetContext.resources

                // Load the image into a Buffer
                val imageBuffer = Buffer().apply {
                    readFrom(res.openRawResource(R.raw.sample))
                }

                MockResponse()
                    .setHeadersDelay(responseDelayMs, TimeUnit.MILLISECONDS)
                    .addHeader("Content-Type", "image/jpeg")
                    .setBody(imageBuffer)
            }
            else ->
                MockResponse()
                    .setHeadersDelay(responseDelayMs, TimeUnit.MILLISECONDS)
                    .setResponseCode(404)
        }
    }

    return MockWebServer().apply {
        setDispatcher(dispatcher)
    }
}
