package org.qommons.data.migration;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;

import org.qommons.SelfDescribed;
import org.qommons.StringUtils;
import org.qommons.fn.FunctionUtils;

public class MigrationSetDef implements SelfDescribed, Comparable<MigrationSetDef> {
	public static final Comparator<MigrationSetDef> SORT = FunctionUtils.printableComparator(MigrationSetDef::compareTo,
		() -> MigrationSetDef.class.getSimpleName() + "::compareTo");

	public final String author;
	public final Instant date;
	private final String theDescription;

	public MigrationSetDef(String author, Instant date, String description) {
		this.author = author;
		this.date = date;
		theDescription = description;
	}

	@Override
	public String getDescription() {
		return theDescription;
	}

	@Override
	public int compareTo(MigrationSetDef o) {
		int comp = date.compareTo(o.date);
		if (comp == 0)
			comp = StringUtils.compareNumberTolerant(author, o.author, true, true);
		return comp;
	}

	@Override
	public int hashCode() {
		return Objects.hash(author, date);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		else if (!(obj instanceof MigrationSetDef))
			return false;
		MigrationSetDef other = (MigrationSetDef) obj;
		return author.equals(other.author) && date.equals(other.date);
	}

	@Override
	public String toString() {
		return author + "@" + date;
	}
}
