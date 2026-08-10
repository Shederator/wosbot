package dev.frostguard.tasks;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.DelayedTaskRegistry;

import dev.frostguard.tasks.alliance.*;
import dev.frostguard.tasks.city.*;
import dev.frostguard.tasks.combat.*;
import dev.frostguard.tasks.dailies.*;
import dev.frostguard.tasks.economy.*;
import dev.frostguard.tasks.events.*;
import dev.frostguard.tasks.exploration.*;
import dev.frostguard.tasks.heroes.*;
import dev.frostguard.tasks.lifecycle.*;
import dev.frostguard.tasks.pets.*;

/**
 * Registers all task factories with the DelayedTaskRegistry.
 * Must be called once at application startup before any tasks are created.
 */
public class TaskRegistrations {

    public static void initialize() {
        DelayedTaskRegistry.registerFactory(TaskRegistrations::createTask);
    }

    private static DelayedTask createTask(TpDailyTaskEnum type, AccountDescriptor profile) {
        return switch (type) {
            // Heroes
            case HERO_RECRUITMENT -> new HeroRecruitmentRoutine(profile, type);
            case EXPERT_AGNES_INTEL -> new ExpertsAgnesIntelRoutine(profile, type);
            case EXPERT_ROMULUS_TAG -> new ExpertsRomulusTagRoutine(profile, type);
            case EXPERT_ROMULUS_TROOPS -> new ExpertsRomulusTroopsRoutine(profile, type);
            case EXPERT_SKILL_TRAINING -> new ExpertSkillTrainingRoutine(profile, type);

            // Economy
            case NOMADIC_MERCHANT -> new NomadicMerchantRoutine(profile, type);
            case BANK -> new BankRoutine(profile, type);
            case SHOP_MYSTERY -> new MysteryShopRoutine(profile, type);
            case GATHER_RESOURCES -> new GatherRoutine(profile, type);
            case GATHER_BOOST -> new GatherSpeedRoutine(profile, type);
            case STOREHOUSE_CHEST -> new StorehouseChestRoutine(profile, type);

            // City
            case WAR_ACADEMY -> new WarAcademyRoutine(profile, type);
            case CRYSTAL_LABORATORY -> new CrystalLaboratoryRoutine(profile, type);
            case CITY_UPGRADE_FURNACE -> new UpgradeBuildingsRoutine(profile, type);
            case CITY_UPGRADE_PRIORITISE_FURNACE -> new PrioritiseFurnaceRoutine(profile, type);
            case CITY_SURVIVORS -> new NewSurvivorsRoutine(profile, type);
            case TRAINING_TROOPS -> new TrainingRoutine(profile, type);
            case RESEARCH -> new ResearchRoutine(profile, type);

            // Dailies
            case VIP_POINTS -> new VipRoutine(profile, type);
            case MAIL_REWARDS -> new MailRewardsRoutine(profile, type);
            case DAILY_MISSIONS -> new DailyMissionRoutine(profile, type);
            case INTEL -> new IntelligenceRoutine(profile, type);

            // Chief Order
            case CHIEF_ORDER_RUSH_JOB -> new ChiefOrderRoutine(profile, type,
                    ChiefOrderRoutine.ChiefOrderType.RUSH_JOB);
            case CHIEF_ORDER_URGENT_MOBILIZATION -> new ChiefOrderRoutine(profile, type,
                    ChiefOrderRoutine.ChiefOrderType.URGENT_MOBILIZATION);
            case CHIEF_ORDER_PRODUCTIVITY_DAY -> new ChiefOrderRoutine(profile, type,
                    ChiefOrderRoutine.ChiefOrderType.PRODUCTIVITY_DAY);

            // Exploration
            case EXPLORATION_CHEST -> new ExplorationRoutine(profile, type);
            case DO_EXPLORATION -> new DoExplorationRoutine(profile, type);
            case LABYRINTH -> new DailyLabyrinthRoutine(profile, type);
            case TREK_SUPPLIES -> new TundraTrekRoutine(profile, type);
            case TREK_AUTOMATION -> new TundraTrekAutoRoutine(profile, type);

            // Pets
            case PET_ADVENTURE -> new PetAdventureChestRoutine(profile, type);
            case PET_SKILLS -> new PetSkillsRoutine(profile, type);
            case LIFE_ESSENCE -> new LifeEssenceRoutine(profile, type);
            case LIFE_ESSENCE_CARING -> new LifeEssenceCaringRoutine(profile, type);

            // Combat
            case BEAST_HUNTING -> new BeastSlayRoutine(profile, type);
            case ARENA -> new ArenaRoutine(profile, type);
            case EVENT_POLAR_TERROR -> new PolarTerrorHuntingRoutine(profile, type);
            case EVENT_BERSERK_CRYPTID -> new ManualRallyJoinRoutine(profile, type);
            case BEAR_TRAP -> new BearTrapRoutine(profile, type);

            // Alliance
            case ALLIANCE_AUTOJOIN -> new AllianceAutojoinRoutine(profile, type);
            case ALLIANCE_TECH -> new AllianceTechRoutine(profile, type);
            case ALLIANCE_SHOP -> new AllianceShopRoutine(profile, type);
            case ALLIANCE_PET_TREASURE -> new PetAllianceTreasuresRoutine(profile, type);
            case ALLIANCE_CHESTS -> new AllianceChestRoutine(profile, type);
            case ALLIANCE_TRIUMPH -> new TriumphRoutine(profile, type);
            case ALLIANCE_MOBILIZATION -> new AllianceMobilizationRoutine(profile, type);
            case ALLIANCE_CHAMPIONSHIP -> new AllianceChampionshipRoutine(profile, type);

            // Events
            case EVENT_TUNDRA_TRUCK -> new TundraTruckEventRoutine(profile, type);
            case EVENT_HERO_MISSION -> new HeroMissionEventRoutine(profile, type);
            case MERCENARY_EVENT -> new MercenaryEventRoutine(profile, type);
            case EVENT_JOURNEY_OF_LIGHT -> new JourneyofLightRoutine(profile, type);
            case EVENT_MYRIAD_BAZAAR -> new MyriadBazaarEventRoutine(profile, type);
            case FISHING_MINIGAME -> new FishingMinigameRoutine(profile, type);

            // Lifecycle
            case INITIALIZE -> new InitializeRoutine(profile, type);
            case SKIP_TUTORIAL -> new SkipTutorialRoutine(profile, type);
            case CREATE_CHARACTER -> new CreateCharacterRoutine(profile, type);
            case TEST_HOOK_LOOP -> new TestHookLoopRoutine(profile, type);
            case DUMMY_TASK -> new DummyRoutine(profile, type);

            default -> null;
        };
    }
}
