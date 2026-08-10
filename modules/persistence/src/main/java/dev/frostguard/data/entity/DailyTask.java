package dev.frostguard.data.entity;

import java.time.LocalDateTime;
import java.util.Objects;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Persisted state of one automated routine execution cycle
 * within the Frostguard scheduling system. Each record binds
 * a {@link DailyTaskTemplate routine type} to a {@link Profile}
 * and tracks when the routine last completed versus when it
 * should fire next.
 */
@Entity
@Table(name = "daily_task")
@Access(AccessType.FIELD)
public class DailyTask {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false, unique = true)
	private Long id;

	@ManyToOne
	@JoinColumn(name = "task_id", nullable = false,
		foreignKey = @ForeignKey(name = "fk_daily_task_tp_daily_task"))
	@OnDelete(action = OnDeleteAction.CASCADE)
	private DailyTaskTemplate routine;

	@ManyToOne
	@JoinColumn(name = "profile_id", nullable = false,
		foreignKey = @ForeignKey(name = "fk_dailytask_profile"))
	@OnDelete(action = OnDeleteAction.CASCADE)
	private Profile owner;

	@Column(name = "last_execution", nullable = false)
	private LocalDateTime completedAt;

	@Column(name = "next_schedule", nullable = false)
	private LocalDateTime nextRunAt;

	@Column(name = "custom_task_name", nullable = true)
	private String customTaskLabel;

	@Column(name = "stamina_minimum_required")
	private Integer staminaMinimumRequired;

	@Column(name = "stamina_regeneration_target")
	private Integer staminaRegenerationTarget;

	@Column(name = "stamina_earliest_runnable_at")
	private LocalDateTime staminaEarliestRunnableAt;

	public DailyTask() {}

	public static DailyTask scheduled(Profile owner, DailyTaskTemplate routine,
	                                  LocalDateTime lastCompletion, LocalDateTime nextRun) {
		DailyTask task = new DailyTask();
		task.owner = owner;
		task.routine = routine;
		task.completedAt = lastCompletion != null ? lastCompletion : LocalDateTime.now();
		task.nextRunAt = nextRun != null ? nextRun : LocalDateTime.now();
		return task;
	}

	public boolean isDue(LocalDateTime asOf) {
		return nextRunAt != null && !nextRunAt.isAfter(asOf);
	}

	public boolean isEnabled() {
		return routine != null;
	}

	public void markRanAt(LocalDateTime timestamp) {
		this.completedAt = timestamp != null ? timestamp : LocalDateTime.now();
	}

	public void scheduleNext(LocalDateTime next) {
		this.nextRunAt = next;
	}

	public boolean belongsToProfile(Long profileId) {
		return owner != null && profileId != null && profileId.equals(owner.getId());
	}

	public boolean hasCustomLabel() {
		return customTaskLabel != null && !customTaskLabel.isBlank();
	}

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }

	public DailyTaskTemplate getRoutine() { return routine; }
	public void setRoutine(DailyTaskTemplate routine) { this.routine = routine; }

	public Profile getOwner() { return owner; }
	public void setOwner(Profile owner) { this.owner = owner; }

	public LocalDateTime getCompletedAt() { return completedAt; }
	public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

	public LocalDateTime getNextRunAt() { return nextRunAt; }
	public void setNextRunAt(LocalDateTime nextRunAt) { this.nextRunAt = nextRunAt; }

	public String getCustomTaskLabel() { return customTaskLabel; }
	public void setCustomTaskLabel(String label) { this.customTaskLabel = label; }

	public Integer getStaminaMinimumRequired() { return staminaMinimumRequired; }
	public void setStaminaMinimumRequired(Integer value) { this.staminaMinimumRequired = value; }

	public Integer getStaminaRegenerationTarget() { return staminaRegenerationTarget; }
	public void setStaminaRegenerationTarget(Integer value) { this.staminaRegenerationTarget = value; }

	public LocalDateTime getStaminaEarliestRunnableAt() { return staminaEarliestRunnableAt; }
	public void setStaminaEarliestRunnableAt(LocalDateTime value) { this.staminaEarliestRunnableAt = value; }

	public void clearStaminaDeferral() {
		staminaMinimumRequired = null;
		staminaRegenerationTarget = null;
		staminaEarliestRunnableAt = null;
	}

	// Compatibility delegates for downstream callers
	public DailyTaskTemplate getDefinition() { return routine; }
	public void setDefinition(DailyTaskTemplate d) { this.routine = d; }
	public Profile getAccount() { return owner; }
	public void setAccount(Profile p) { this.owner = p; }
	public LocalDateTime getPreviousRun() { return completedAt; }
	public void setPreviousRun(LocalDateTime t) { this.completedAt = t; }
	public LocalDateTime getScheduledAt() { return nextRunAt; }
	public void setScheduledAt(LocalDateTime t) { this.nextRunAt = t; }

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof DailyTask other)) return false;
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() { return Objects.hashCode(id); }

	@Override
	public String toString() {
		return "DailyTask[" + id + " routine=" + (routine != null ? routine.getId() : "?")
			+ " owner=" + (owner != null ? owner.getId() : "?")
			+ " next=" + nextRunAt + "]";
	}
}
