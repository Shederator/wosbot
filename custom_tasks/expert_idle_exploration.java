package dev.frostguard.engine.listener.task.impl;

import java.time.LocalDateTime;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;

public class expert_idle_exploration extends DelayedTask {

    public expert_idle_exploration(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected Object getDistinctKey() {
        return "expert_idle_exploration";
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    @Override
    protected void execute() {
        logInfo("Starting task: 'expert_idle_exploration'");
        int __state = 2;
        while (__state != -1) {
            checkPreemption();
            switch (__state) {
                case 2: {
                    tapRandomPoint(new PointData(1, 500), new PointData(25, 600));
                    __state = 3;
                    break;
                }
                case 3: {
                    swipe(new PointData(200, 750), new PointData(260, 400));
                    __state = 11;
                    break;
                }
                case 4: {
                    sleepTask(2000L);
                    __state = 5;
                    break;
                }
                case 5: {
                    tapRandomPoint(new PointData(384, 810), new PointData(420, 835));
                    __state = 6;
                    break;
                }
                case 6: {
                    sleepTask(200L);
                    __state = 12;
                    break;
                }
                case 7: {
                    tapRandomPoint(new PointData(500, 30), new PointData(600, 50));
                    __state = 8;
                    break;
                }
                case 8: {
                    tapRandomPoint(new PointData(400, 150), new PointData(500, 300));
                    __state = 9;
                    break;
                }
                case 9: {
                    tapRandomPoint(new PointData(580, 1150), new PointData(670, 1200));
                    __state = 10;
                    break;
                }
                case 10: {
                    tapRandomPoint(new PointData(210, 820), new PointData(490, 850));
                    __state = -1;
                    break;
                }
                case 11: {
                    swipe(new PointData(200, 750), new PointData(260, 400));
                    __state = 4;
                    break;
                }
                case 12: {
                    tapRandomPoint(new PointData(500, 510), new PointData(650, 610));
                    __state = 13;
                    break;
                }
                case 13: {
                    sleepTask(3000L);
                    __state = 7;
                    break;
                }
                default:
                    __state = -1;
                    break;
            }
        }

        int __interval = getRepeatIntervalMinutes();
        if (__interval > 0) {
            reschedule(LocalDateTime.now().plusMinutes(__interval));
            logInfo("Task rescheduled in " + __interval + " minutes.");
        } else {
            setRecurring(false);
            logInfo("Task interval is 0, disabling recurrence.");
        }
        logInfo("Generated task complete.");
    }
}
