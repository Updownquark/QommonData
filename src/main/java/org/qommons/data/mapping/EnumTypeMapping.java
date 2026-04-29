package org.qommons.data.mapping;

import org.qommons.Named;
import org.qommons.data.types.EnumType;
import org.qommons.data.types.EnumValue;

public class EnumTypeMapping<E extends Enum<E>> implements Named {
	private final EnumType theGenericType;
	public final Class<E> codeType;
	private final EnumValue[] theValueMapping;

	public EnumTypeMapping(EnumType genericType, Class<? extends Enum<?>> codeType) {
		this.theGenericType = genericType;
		this.codeType = (Class<E>) codeType;
		theValueMapping = new EnumValue[genericType.getValues().size()];
		int i = 0;
		for (Enum<?> value : codeType.getEnumConstants())
			theValueMapping[i++] = genericType.getValue(value.name());
	}

	@Override
	public String getName() {
		return theGenericType.getName();
	}

	public EnumValue getCodeOrderedEnum(int ordinal) {
		return theValueMapping[ordinal];
	}

	@Override
	public String toString() {
		return theGenericType.getName();
	}
}
