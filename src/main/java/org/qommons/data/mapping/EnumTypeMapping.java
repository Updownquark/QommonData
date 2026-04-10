package org.qommons.data.mapping;

import org.qommons.Named;
import org.qommons.data.types.EnumType;
import org.qommons.data.types.EnumValue;

public class EnumTypeMapping<E extends Enum<E>> implements Named {
	private final EnumType genericType;
	public final Class<E> codeType;
	private final EnumValue[] theValueMapping;

	public EnumTypeMapping(EnumType genericType, Class<? extends Enum<?>> codeType) {
		this.genericType = genericType;
		this.codeType = (Class<E>) codeType;
		theValueMapping = new EnumValue[genericType.getValues().size()];
		int i = 0;
		for (Enum<?> value : codeType.getEnumConstants())
			theValueMapping[i++] = genericType.getValue(value.name());
	}

	@Override
	public String getName() {
		return genericType.getName();
	}

	public EnumValue getCodeOrderedEnum(int ordinal) {
		return theValueMapping[ordinal];
	}
}
