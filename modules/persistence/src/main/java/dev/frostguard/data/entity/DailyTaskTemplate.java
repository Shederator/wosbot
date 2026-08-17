package dev.frostguard.data.entity;

import java.time.LocalDateTime;

import dev.frostguard.api.configs.TpDailyTaskEnum;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Catalog entry for a Frostguard automated routine type.
 * Seeded from {@link TpDailyTaskEnum} at startup; individual
 * {@link DailyTask} schedule records reference this as their
 * routine definition.
 */
@Entity
@Table(name = "tp_daily_task")
@Access(AccessType.FIELD)
public class DailyTaskTemplate {

	@Id
	@Column(name = "id", nullable = false, unique = true)
	private Integer id;

	@Column(name = "task_name", nullable = false, unique = true)
	private String routineName;

	protected DailyTaskTemplate() {}

	public static DailyTaskTemplate fromRoutineType(TpDailyTaskEnum routine) {
		DailyTaskTemplate tpl = new DailyTaskTemplate();
		tpl.id = routine.getId();
		tpl.routineName = routine.getName();
		return tpl;
	}

	public DailyTask createTaskFor(Profile owner, LocalDateTime initialRun) {
		return DailyTask.scheduled(owner, this, LocalDateTime.now(), initialRun);
	}

	public boolean isInitiallyEnabled() { return true; }

	public String routineType() { return routineName; }

	public boolean matchesRoutine(TpDailyTaskEnum candidate) {
		return candidate != null && id != null && id.equals(candidate.getId());
	}

	public Integer getId() { return id; }
	public void setId(Integer id) { this.id = id; }

	public String getRoutineName() { return routineName; }
	public void setRoutineName(String name) { this.routineName = name; }

	// Compatibility delegates for downstream callers
	public String getJobLabel() { return routineName; }
	public void setJobLabel(String label) { this.routineName = label; }

	@Deprecated
	public DailyTaskTemplate(TpDailyTaskEnum taskType) {
		this.id = taskType.getId();
		this.routineName = taskType.getName();
	}

	@Override
	public String toString() {
		return "DailyTaskTemplate[" + id + ":" + routineName + "]";
	}
}
