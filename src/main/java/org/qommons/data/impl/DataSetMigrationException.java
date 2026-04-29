package org.qommons.data.impl;

public class DataSetMigrationException extends Exception {
	public enum MigrationFailureCause {
		NotADataSet("This directory is not a data set"), //
		InvalidDataSet("This data set directory is invalid"), //
		IncompatibleDataSet("This data set directory's data is incompatible with the application"), //
		IncompatibleVersion("This data set directory's data is incompatible with this version of the application");

		public final String message;

		private MigrationFailureCause(String message) {
			this.message = message;
		}
	}

	private final MigrationFailureCause theCause;

	public DataSetMigrationException(MigrationFailureCause cause) {
		super(cause.message);
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
