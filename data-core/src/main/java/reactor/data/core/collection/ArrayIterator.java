/*
 * Copyright (c) 2011-2014 GoPivotal, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.data.core.collection;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * An efficient implementation of {@link java.util.Iterator} that uses an array and an index counter.
 *
 * @author Jon Brisbin
 */
public class ArrayIterator<T> implements Iterator<T> {

	private final T[] values;
	private final int length;
	private int currIdx = 0;

	public ArrayIterator(T[] values) {
		this.values = values;
		this.length = values.length;
	}

	@Override
	public boolean hasNext() {
		return currIdx < length;
	}

	@Override
	public T next() {
		try {
			return values[currIdx++];
		} catch(ArrayIndexOutOfBoundsException ignored) {
			throw new NoSuchElementException("index " + currIdx);
		}
	}

	@Override
	public void remove() {
		throw new UnsupportedOperationException();
	}

}
