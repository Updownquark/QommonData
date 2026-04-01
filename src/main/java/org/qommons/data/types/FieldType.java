package org.qommons.data.types;

import java.util.List;
import java.util.Objects;

public interface FieldType {
	public static class SimpleType<F> implements FieldType {
		public final Class<F> type;

		public SimpleType(Class<F> type) {
			this.type = type;
		}

		@Override
		public int hashCode() {
			return type.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof SimpleType && type.equals(((SimpleType<?>) obj).type);
		}

		@Override
		public String toString() {
			return type.toString();
		}
	}

	public static class ParameterizedType implements FieldType {
		public final Class<?> rawType;
		public final List<FieldType> componentTypes;

		public ParameterizedType(Class<?> rawType, List<FieldType> componentTypes) {
			this.rawType = rawType;
			this.componentTypes = componentTypes;
		}

		@Override
		public int hashCode() {
			return Objects.hash(rawType, componentTypes);
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			else if (!(obj instanceof ParameterizedType))
				return false;
			ParameterizedType other = (ParameterizedType) obj;
			return rawType.equals(other.rawType) && componentTypes.equals(other.componentTypes);
		}

		@Override
		public String toString() {
			StringBuilder str = new StringBuilder();
			str.append(rawType.getName());
			str.append('<');
			for (int i = 0; i < componentTypes.size(); i++) {
				if (i > 0)
					str.append(", ");
				str.append(componentTypes.get(i));
			}
			return str.append('>').toString();
		}
	}
}
