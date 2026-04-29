package org.qommons.data.types.modifiable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.qommons.QommonsUtils;
import org.qommons.data.migration.MigrationException;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.FieldMapping;
import org.qommons.data.types.FieldType;
import org.qommons.data.values.GenericEntity;
import org.qommons.io.FilePosition;

public class ModifiableEntityField<F> implements EntityField<F> {
	private final ModifiableEntityType theOwner;
	private String theName;
	private final FieldType<F> theType;
	private final boolean isId;
	private final Unmodifiable<F> theUnmodifiable;
	private final FieldMapping<F, ?, ?> theMapping;
	private FieldMapping<?, ?, ?> theMappingReference;
	private FieldMapping<?, ?, ?> theIndexReference;
	private final Set<FieldMapping<?, ?, ?>> theAncillaryMappingReferences;

	ModifiableEntityField(ModifiableEntityType owner, String name, FieldType<F> type, boolean isId, FieldMappingPrecursor<?, ?> mapping,
		FilePosition source) {
		theOwner = owner;
		theName = name;
		theType = type;
		this.isId = isId;
		theMapping = mapping == null ? null : mapping.createMapping(this);

		theAncillaryMappingReferences = new HashSet<>();
		theUnmodifiable = new Unmodifiable<>(this);
	}

	@Override
	public ModifiableEntityType getOwner() {
		return theOwner;
	}

	@Override
	public String getName() {
		return theName;
	}

	public ModifiableEntityField<F> setName(String newName, FilePosition source) throws MigrationException {
		theOwner.renameField(this, newName, source);
		return this;
	}

	void doSetName(String newName) {
		theName = newName;
	}

	@Override
	public FieldType<F> getType() {
		return theType;
	}

	@Override
	public boolean isId() {
		return isId;
	}

	@Override
	public FieldMapping<F, ?, ?> getMapping() {
		return theMapping;
	}

	@Override
	public FieldMapping<?, ?, ?> getMappingReference() {
		return theMappingReference;
	}

	void setReference(FieldMapping<?, ?, ?> reference) {
		theMappingReference = reference;
	}

	@Override
	public FieldMapping<?, ?, ?> getIndexReference() {
		return theIndexReference;
	}

	void setIndexReference(FieldMapping<?, ?, ?> reference) {
		theIndexReference = reference;
	}

	@Override
	public Set<FieldMapping<?, ?, ?>> getAncillaryMappingReferences() {
		return Collections.unmodifiableSet(theAncillaryMappingReferences);
	}

	void addAncillaryReference(FieldMapping<?, ?, ?> ancillaryReference) {
		theAncillaryMappingReferences.add(ancillaryReference);
	}

	void removeAncillaryReference(FieldMapping<?, ?, ?> keyReference) {
		theAncillaryMappingReferences.remove(keyReference);
	}

	public EntityField<F> unmodifiableView() {
		return theUnmodifiable;
	}

	public void delete() {
		theOwner.removeField(this);
	}

	public StringBuilder append(StringBuilder str) {
		str.append(theName);
		if (isId)
			str.append('*');
		str.append(" (").append(theType).append(')');
		if (theMapping != null)
			theMapping.append(str.append(" mapped by "));
		return str;
	}

	@Override
	public String toString() {
		return theOwner + "." + getName() + " (" + theType + ")";
	}

	static class Unmodifiable<F> implements EntityField<F> {
		private final ModifiableEntityField<F> theSource;
		private final FieldMapping<F, ?, ?> theMapping;
		private final FieldType<F> theType;

		Unmodifiable(ModifiableEntityField<F> source) {
			theSource = source;
			theMapping = unmodifiableMapping(theSource.getMapping());
			theType = unmodifiable(source.getType());
		}

		<F2, K, S> FieldMapping<F2, K, S> unmodifiableMapping(FieldMapping<?, ?, ?> mapping) {
			if (mapping == null)
				return null;
			return new FieldMapping<>(//
				mapping.parentField == theSource ? (EntityField<F2>) this
					: ((ModifiableEntityField<F2>) mapping.parentField).unmodifiableView(), //
					((ModifiableEntityField<GenericEntity>) mapping.mappedReferenceField).unmodifiableView(), //
					mapping.keyField == null ? null : ((ModifiableEntityField<K>) mapping.keyField).unmodifiableView(),
						mapping.indexField == null ? null : ((ModifiableEntityField<Integer>) mapping.indexField).unmodifiableView(),
							mapping.sortByField == null ? null : ((ModifiableEntityField<S>) mapping.sortByField).unmodifiableView(),
								mapping.parentIsOwner);
		}

		static <F> FieldType<F> unmodifiable(FieldType<F> type) {
			if (type instanceof ModifiableEntityType)
				return (FieldType<F>) ((ModifiableEntityType) type).unmodifiableView();
			else if (type instanceof ModifiableEnumType)
				return (FieldType<F>) ((ModifiableEnumType) type).unmodifiableView();
			else if (type instanceof FieldType.ParameterizedType) {
				return ((FieldType.ParameterizedType<F>) type).map(Unmodifiable::unmodifiable);
			} else
				return type;
		}

		ModifiableEntityField<F> getSource() {
			return theSource;
		}

		@Override
		public EntityType getOwner() {
			return theSource.getOwner().unmodifiableView();
		}

		@Override
		public String getName() {
			return theSource.getName();
		}

		@Override
		public FieldType<F> getType() {
			return theType;
		}

		@Override
		public boolean isId() {
			return theSource.isId();
		}

		@Override
		public FieldMapping<F, ?, ?> getMapping() {
			return theMapping;
		}

		@Override
		public FieldMapping<?, ?, ?> getMappingReference() {
			return unmodifiableMapping(theSource.getMappingReference());
		}

		@Override
		public FieldMapping<?, ?, ?> getIndexReference() {
			return unmodifiableMapping(theSource.getIndexReference());
		}

		@Override
		public Set<FieldMapping<?, ?, ?>> getAncillaryMappingReferences() {
			return QommonsUtils.filterMapDistinct(theSource.getAncillaryMappingReferences(), null, this::unmodifiableMapping);
		}

		@Override
		public String toString() {
			return theSource.toString();
		}
	}
}
