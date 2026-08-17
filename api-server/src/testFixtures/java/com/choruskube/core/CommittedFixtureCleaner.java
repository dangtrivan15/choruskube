package com.choruskube.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Undoes by hand what a rolled-back test transaction would have undone for us.
 *
 * <p>Almost every test in this suite is {@code @Transactional} and never commits, and several
 * assert on a globally EMPTY board — {@code RoadmapTimelineServiceTest} says so in its own comment.
 * One TestContainers database backs the whole suite, so a test that genuinely has to commit (an
 * end-to-end MockMvc flow, or anything exercising a {@code REQUIRES_NEW} transaction, which takes
 * its own connection and cannot see uncommitted rows) breaks that assumption for every class that
 * runs after it — intermittently, depending on discovery order or a {@code --tests} filter.
 *
 * <p>Such a class registers what it creates and calls {@link #deleteAll()} from {@code @AfterEach}.
 *
 * <p>The delete order is dictated by foreign keys, and two of them are easy to get wrong:
 *
 * <ul>
 *   <li>{@code workflow_run.task_id} is a plain reference with no {@code ON DELETE} rule, so runs
 *       must go before the Epic delete cascades away their Tasks.
 *   <li>{@code repo_group_member.git_repo_id} likewise, so membership rows must go before the
 *       {@code software_project} rows on either end of them.
 * </ul>
 *
 * Everything else cascades: Epic → Story → Task, {@code software_project} → {@code git_repo} /
 * {@code repo_group}, and workflow_run → node_execution / run_pull_request.
 */
public final class CommittedFixtureCleaner {

    private final JdbcTemplate jdbc;
    private final List<UUID> epicIds = new ArrayList<>();
    private final List<UUID> softwareProjectIds = new ArrayList<>();
    private final List<UUID> autopilotIds = new ArrayList<>();

    public CommittedFixtureCleaner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Registers an Epic; its Stories and Tasks are removed with it. */
    public UUID trackEpic(UUID id) {
        epicIds.add(id);
        return id;
    }

    /** Registers a GitRepo or RepoGroup by its {@code software_project} id. */
    public UUID trackSoftwareProject(UUID id) {
        softwareProjectIds.add(id);
        return id;
    }

    public UUID trackAutopilot(UUID id) {
        autopilotIds.add(id);
        return id;
    }

    public void deleteAll() {
        if (!epicIds.isEmpty()) {
            Object[] epics = epicIds.toArray();
            String tasksUnderEpics = "SELECT t.id FROM task t JOIN story s ON t.story_id = s.id WHERE s.epic_id IN ("
                    + placeholders(epicIds) + ")";
            // work_item_dependency holds polymorphic ids and no foreign key at all, so a stray row
            // would survive every cascade below without ever failing.
            jdbc.update(
                    "DELETE FROM work_item_dependency WHERE blocking_item_id IN (" + tasksUnderEpics
                            + ") OR blocked_item_id IN (" + tasksUnderEpics + ")",
                    concat(epics, epics));
            jdbc.update("DELETE FROM workflow_run WHERE task_id IN (" + tasksUnderEpics + ")", epics);
            jdbc.update("DELETE FROM epic WHERE id IN (" + placeholders(epicIds) + ")", epics);
        }
        if (!autopilotIds.isEmpty()) {
            jdbc.update(
                    "DELETE FROM autopilot WHERE id IN (" + placeholders(autopilotIds) + ")", autopilotIds.toArray());
        }
        if (!softwareProjectIds.isEmpty()) {
            Object[] projects = softwareProjectIds.toArray();
            String in = placeholders(softwareProjectIds);
            jdbc.update(
                    "DELETE FROM repo_group_member WHERE repo_group_id IN (" + in + ") OR git_repo_id IN (" + in + ")",
                    concat(projects, projects));
            jdbc.update("DELETE FROM software_project WHERE id IN (" + in + ")", projects);
        }
        epicIds.clear();
        autopilotIds.clear();
        softwareProjectIds.clear();
    }

    private static String placeholders(List<UUID> ids) {
        return String.join(",", Collections.nCopies(ids.size(), "?"));
    }

    private static Object[] concat(Object[] a, Object[] b) {
        Object[] all = new Object[a.length + b.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }
}
