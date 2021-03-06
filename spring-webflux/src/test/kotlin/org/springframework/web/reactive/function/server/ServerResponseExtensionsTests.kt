/*
 * Copyright 2002-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.reactive.function.server

import com.nhaarman.mockito_kotlin.mock
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnitRunner
import org.reactivestreams.Publisher

/**
 * Mock object based tests for [ServerResponse] Kotlin extensions
 *
 * @author Sebastien Deleuze
 */
@RunWith(MockitoJUnitRunner::class)
class ServerResponseExtensionsTests {

	@Mock(answer = Answers.RETURNS_MOCKS)
	lateinit var bodyBuilder: ServerResponse.BodyBuilder


	@Test
	fun `BodyBuilder#body with Publisher and reified type parameters`() {
		val body = mock<Publisher<Foo>>()
		bodyBuilder.body(body)
		verify(bodyBuilder, times(1)).body(body, Foo::class.java)
	}

	class Foo
}
