package org.qommons.data.impl;

public class DataSetMigrationException extends Exception {
	public enum MigrationFailureCause {
		NotADataSet, InvalidDataSet, IncompatibleDataSet, IncompatibleVersion;
	}

	private final MigrationFailureCause theCause;

	public DataSetMigrationException(MigrationFailureCause cause) {
		theCause = cause;
	}

	public DataSetMigrationException(MigrationFailureCause cause, String message) {
		super(message);
		theCause = cause;
	}

	public DataSetMigrationException(MigrationFailureCause cause, String message, Throwable causeEx) {
		super(message, causeEx);
		theCause = cause;
	}

	public MigrationFailureCause getFailureCause() {
		return theCause;
	}
}
