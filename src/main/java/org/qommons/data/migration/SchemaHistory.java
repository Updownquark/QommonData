package org.qommons.data.migration;

import org.qommons.collect.BetterCollections;
import org.qommons.collect.BetterSortedSet;
import org.qommons.config.QonfigInterpretationException;
import org.qommons.config.QonfigInterpreterCore;
import org.qommons.data.types.modifiable.ModifiableEntityTypeSet;
import org.qommons.fn.FunctionUtils;
import org.qommons.tree.BetterTreeSet;

public class SchemaHistory {
	public static final String HISTORY = "Schema History";

	public static SchemaHistory get(QonfigInterpreterCore.CoreSession session) throws QonfigInterpretationException {
		Object history = session.get(HISTORY);
		if (history instanceof SchemaHistory)
			return (SchemaHistory) history;
		else if (history == null)
			throw new QonfigInterpretationException("No " + HISTORY + " installed in session", session.getElement().getFilePosition());
		else
			throw new QonfigInterpretationException(HISTORY + " is not a " + SchemaHistory.class.getSimpleName(),
				session.getElement().getFilePosition());
	}

	private final BetterSortedSet<MigrationSet> theMigrations;
	private final ModifiableEntityTypeSet theTypeSet;

	public SchemaHistory(QonfigInterpreterCore.CoreSession session) throws QonfigInterpretationException {
		theMigrations = BetterTreeSet.createTreeSet(FunctionUtils.COMPARABLE_COMPARE);
		theTypeSet = new ModifiableEntityTypeSet();
		session.put(HISTORY, this);
		for (QonfigInterpreterCore.CoreSession migSetSession : session.forChildren("migrations")) {
			theMigrations.add(migSetSession.interpret(MigrationSet.class));
		}
	}

	public BetterSortedSet<MigrationSet> getMigrations() {
		return BetterCollections.unmodifiableSortedSet(theMigrations);
	}

	public ModifiableEntityTypeSet getTypeSet() {
		return theTypeSet;
	}
}
