package dev.eiriksb.stad.colony;

/** The kinds of orders players give citizens, and the lowest rank allowed to give each. */
public enum OrderKind {
    /** a newcomer is asked to join the town */
    INVITE(Rank.OFFICER),
    /** a citizen is told to leave the town */
    DISMISS(Rank.OFFICER),
    /** a citizen is given a job (or told to stop working) */
    ASSIGN_JOB(Rank.OFFICER),
    /** a building is planned for the builders (no citizen in particular) */
    PLAN_BUILDING(Rank.OFFICER);

    public final Rank minRank;

    OrderKind(Rank minRank) {
        this.minRank = minRank;
    }
}
