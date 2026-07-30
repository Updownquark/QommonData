package org.qommons.data.types;

import java.util.Arrays;

public class TupleFieldValue {
	private final Object[] values;

	public TupleFieldValue(int length) {
		this.values = new Object[length];
	}

	public int length() {
		return values.length;
	}

	public Object get(int index) {
		return values[index];
	}

	public TupleFieldValue set(int index, Object value) {
		values[index] = value;
		return this;
	}

	public TupleFieldValue copy() {
		TupleFieldValue copy = new TupleFieldValue(length());
		System.arraycopy(values, 0, copy.values, 0, values.length);
		return copy;
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(values);
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof TupleFieldValue && Arrays.equals(values, ((TupleFieldValue) obj).values);
	}

	@Override
	public String toString() {
		return Arrays.toString(values);
	}
}
