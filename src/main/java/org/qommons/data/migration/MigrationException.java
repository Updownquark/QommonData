package org.qommons.data.migration;

import org.qommons.io.FilePosition;
import org.qommons.io.TextParseException;

public class MigrationException extends TextParseException {
	public MigrationException(String s, FilePosition position, Throwable cause) {
		super(s, position, cause);
	}

	public MigrationException(String s, FilePosition position) {
		super(s, position);
	}
}
