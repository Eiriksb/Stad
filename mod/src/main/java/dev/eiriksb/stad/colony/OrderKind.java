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
    PLAN_BUILDING(Rank.OFFICER),
    /** a building someone made becomes one of the town's (no citizen in particular) */
    ADOPT_BUILDING(Rank.OFFICER),
    /** a citizen is given a home: a bed in one of the town's houses (arg: the building's id) */
    ASSIGN_HOME(Rank.OFFICER),
    /** a town declares war on another, or raids it (no citizen in particular) */
    DECLARE_WAR(Rank.OFFICER),
    /** a guard is ordered to march with a raid (arg: the town raided) */
    MARCH(Rank.OFFICER),
    /** a citizen is sent to another town as an envoy (arg: the town) */
    ENVOY(Rank.OFFICER);

    public final Rank minRank;

    OrderKind(Rank minRank) {
        this.minRank = minRank;
    }
}
