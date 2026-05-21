package org.qommons.data.impl;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.qommons.IterableUtils;
import org.qommons.ThreadConstraint;
import org.qommons.Transaction;
import org.qommons.data.types.EntityField;
import org.qommons.data.types.EntityType;
import org.qommons.data.types.EntityTypeSet;
import org.qommons.data.types.modifiable.ModifiableEntityType;
import org.qommons.data.types.modifiable.ModifiableEntityTypeSet;
import org.qommons.data.values.GenericEntity;
import org.qommons.data.values.GenericEntitySet;

public class FilteredEntitySetView implements GenericEntitySet {
	private final GenericEntitySet theSource;
	private final EntityTypeSet theDataTypes;
	private final Map<String, EntityView> theEntityViews;

	public FilteredEntitySetView(GenericEntitySet source, Set<String> affectedEntities, Map<String, Set<String>> requiredFields) {
		theSource = source;
		EntityTypeSet types = source.getTypes();
		if (types instanceof ModifiableEntityTypeSet)
			theDataTypes = ((ModifiableEntityTypeSet) types).unmodifiableView();
		else
			theDataTypes = types;
		theEntityViews = new HashMap<>();
		for (String affected : affectedEntities) {
			EntityType type = theDataTypes.getEntityType(affected);
			if (type == null)
				throw new IllegalArgumentException("No such entity type to affect: " + affected);
			Set<String> fields = requiredFields.get(affected);
			theEntityViews.put(affected, new EntityView(type, true, fields));
		}
		for (Map.Entry<String, Set<String>> requiredEntity : requiredFields.entrySet()) {
			theEntityViews.computeIfAbsent(requiredEntity.getKey(), entity -> {
				EntityType type = theDataTypes.getEntityType(entity);
				if (type == null)
					throw new IllegalArgumentException("No such entity type to affect: " + entity);
				return new EntityView(type, false, requiredEntity.getValue());
			});
		}
	}

	@Override
	public EntityTypeSet getTypes() {
		return theDataTypes;
	}

	@Override
	public Transaction lock(boolean tryOnly) {
		return theSource.lock(tryOnly);
	}

	@Override
	public Transaction lockWrite(boolean tryOnly, Object cause) {
		return theSource.lockWrite(tryOnly, cause);
	}

	@Override
	public CoreId getCoreId() {
		return theSource.getCoreId();
	}

	@Override
	public ThreadConstraint getThreadConstraint() {
		return theSource.getThreadConstraint();
	}

	private EntityView getView(String typeName) {
		EntityType type = theDataTypes.getEntityType(typeName);
		if (type == null)
			throw new IllegalArgumentException("No such entity type '" + typeName + "'");
		return getView(type);
	}

	private EntityView getView(EntityType type) {
		EntityView view = theEntityViews.get(type.getName());
		if (view != null)
			return view;
		for (EntityType sup : type.getSuperTypes()) {
			view = getView(sup);
			if (view != null)
				return view;
		}
		return null;
	}

	@Override
	public Iterable<GenericEntity> getEntities(String typeName) throws IllegalArgumentException, IOException {
		EntityView view = getView(typeName);
		if (view == null)
			throw new IllegalArgumentException("Entities for type " + typeName + " are not provided here");
		Iterable<GenericEntity> sourceEntities = theSource.getEntities(typeName);
		return IterableUtils.map(sourceEntities, view);
	}

	@Override
	public GenericEntity getEntity(String typeName, Object... id) throws IllegalArgumentException, IOException {
		EntityView view = getView(typeName);
		if (view == null)
			throw new IllegalArgumentException("Entities for type " + typeName + " are not provided here");
		GenericEntity source = theSource.getEntity(typeName, id);
		return source == null ? null : view.apply(source);
	}

	@Override
	public boolean isMember(GenericEntity entity) {
		return entity instanceof FilteredEntityView && theSource.isMember(((FilteredEntityView) entity).getSource());
	}

	@Override
	public GenericEntity createEntity(String typeName) {
		EntityView view = getView(typeName);
		if (view == null || !view.isAffected)
			throw new IllegalArgumentException("Entities for type " + typeName + " cannot be created here");
		GenericEntity sourceEntity = theSource.createEntity(typeName);
		return view.apply(sourceEntity);
	}

	@Override
	public GenericEntity createEntity(String typeName, Object... ids) {
		EntityView view = getView(typeName);
		if (view == null || !view.isAffected)
			throw new IllegalArgumentException("Entities for type " + typeName + " cannot be created here");
		GenericEntity sourceEntity = theSource.createEntity(typeName, ids);
		return view.apply(sourceEntity);
	}

	private class EntityView implements Function<GenericEntity, GenericEntity> {
		final EntityType theType;
		final boolean isAffected;
		final Set<String> requiredFields;

		EntityView(EntityType type, boolean isAffected, Set<String> requiredFields) {
			theType = type;
			this.isAffected = isAffected;
			this.requiredFields = requiredFields;
			for (String field : requiredFields) {
				if (type.getField(field) == null)
					throw new IllegalArgumentException("No such field '" + field + "' on entity type " + type);
			}
		}

		@Override
		public GenericEntity apply(GenericEntity sourceEntity) {
			return new FilteredEntityView(FilteredEntitySetView.this, sourceEntity, this);
		}
	}

	static class FilteredEntityView implements GenericEntity {
		private final GenericEntitySet theEntitySet;
		private final GenericEntity theSource;
		private final EntityView theView;

		FilteredEntityView(GenericEntitySet entitySet, GenericEntity source, EntityView view) {
			theEntitySet = entitySet;
			theSource = source;
			theView = view;
		}

		GenericEntity getSource() {
			return theSource;
		}

		@Override
		public EntityType getType() {
			EntityType type = theSource.getType();
			if (type instanceof ModifiableEntityType)
				return ((ModifiableEntityType) type).unmodifiableView();
			else
				return type;
		}

		@Override
		public GenericEntitySet getEntitySet() {
			return theEntitySet;
		}

		@Override
		public <T> T get(EntityField<T> field) {
			if (!field.isId() && !theView.requiredFields.contains(field.getName()))
				throw new IllegalArgumentException("Values for field " + field + " are not provided here");
			return theSource.get(field);
		}

		@Override
		public String isEnabled(EntityField<?> field) {
			if (!theView.isAffected)
				return "Entities for type " + theView.theType + " cannot be affected here";
			return theSource.isEnabled(field);
		}

		@Override
		public String isAcceptable(EntityField<?> field, Object value) {
			if (!theView.isAffected)
				return "Entities for type " + theView.theType + " cannot be affected here";
			return theSource.isAcceptable(field, value);
		}

		@Override
		public GenericEntity set(EntityField<?> field, Object value) {
			if (!theView.isAffected)
				throw new UnsupportedOperationException("Entities for type " + theView.theType + " cannot be affected here");
			theSource.set(field, value);
			return this;
		}

		@Override
		public String canDelete() {
			if (!theView.isAffected)
				return "Entities for type " + theView.theType + " cannot be affected here";
			return theSource.canDelete();
		}

		@Override
		public void delete() {
			if (!theView.isAffected)
				throw new UnsupportedOperationException("Entities for type " + theView.theType + " cannot be affected here");
			theSource.delete();
		}

		@Override
		public boolean isDeleted() {
			return theSource.isDeleted();
		}

		@Override
		public String toString() {
			return theSource.toString();
		}
	}
}
