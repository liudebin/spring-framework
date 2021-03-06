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

package org.springframework.core.annotation;

import org.junit.Test;

import javax.annotation.Priority;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 *
 * @author Stephane Nicoll
 */
public class OrderUtilsTests {

	@Test
	public void getSimpleOrder() {
		assertEquals(Integer.valueOf(50), OrderUtils.getOrder(SimpleOrder.class, null));
	}

	@Test
	public void getPriorityOrder() {
		assertEquals(Integer.valueOf(55), OrderUtils.getOrder(SimplePriority.class, null));
	}

	@Test
	public void getOrderWithBoth() {
		assertEquals(Integer.valueOf(50), OrderUtils.getOrder(OrderAndPriority.class, null));
	}

	@Test
	public void getDefaultOrder() {
		assertEquals(33, OrderUtils.getOrder(NoOrder.class, 33));
	}

	@Test
	public void getPriorityValueNoAnnotation() {
		assertNull(OrderUtils.getPriority(SimpleOrder.class));
	}

	@Test
	public void getPriorityValue() {
		assertEquals(Integer.valueOf(55), OrderUtils.getPriority(OrderAndPriority.class));
	}

	@Order(50)
	private static class SimpleOrder {}

	@Priority(55)
	private static class SimplePriority {}

	@Order(50)
	@Priority(55)
	private static class OrderAndPriority {}

	private static class NoOrder {}

}
