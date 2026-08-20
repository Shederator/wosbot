package dev.frostguard.tasks;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.DelayedTaskRegistry;

import dev.frostguard.tasks.alliance.*;
import dev.frostguard.tasks.analytics.GameAnalyticsRoutine;
import dev.frostguard.tasks.city.*;
import dev.frostguard.tasks.combat.*;
import dev.frostguard.tasks.social.*;
import dev.frostguard.tasks.dailies.*;
import dev.frostguard.tasks.economy.*;
import dev.frostguard.tasks.events.*;
import dev.frostguard.tasks.exploration.*;
import dev.frostguard.tasks.heroes.*;
import dev.frostguard.tasks.lifecycle.*;
import dev.frostguard.tasks.pets.*;
import dev.frostguard.tasks.shop.*;

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
            case GAME_ANALYTICS_LABYRINTH, GAME_ANALYTICS_POWER ->
                    new GameAnalyticsRoutine(profile, type);
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
            // TRAINING_TROOPS retired (configKey nulled, never auto-
            // scheduled) in favor of the 3 independent tasks below -- kept mapped here
            // only so the switch stays exhaustive / a stray manual construction doesn't NPE.
            case TRAINING_TROOPS -> new TrainingRoutine(profile, type);
            case TRAINING_INFANTRY -> new TrainingRoutine(profile, type);
            case TRAINING_LANCER -> new TrainingRoutine(profile, type);
            case TRAINING_MARKSMAN -> new TrainingRoutine(profile, type);
            case HEAL_INJURED_TROOPS -> new HealInjuredRoutine(profile, type);
            case MONUMENT -> new MonumentRoutine(profile, type);
            case EVENT_HALL_OF_CHIEFS -> new EventClaimRoutine(profile, type, EventClaimRoutine.EventKind.HALL_OF_CHIEFS);
            case EVENT_DEFEAT_BEASTS -> new EventClaimRoutine(profile, type, EventClaimRoutine.EventKind.DEFEAT_NEARBY_BEASTS);
            case EVENT_BROTHERS_IN_ARMS -> new EventClaimRoutine(profile, type, EventClaimRoutine.EventKind.BROTHERS_IN_ARMS);
            case EVENT_HERO_RALLY_CLAIM -> new HeroRallyClaimRoutine(profile, type);
            case EVENT_LUCKY_CHIP_SUPPLY -> new LuckyChipSupplyRoutine(profile, type);
            case SHOP_CUSTOM_ARMAMENT_CHEST -> new CustomArmamentChestRoutine(profile, type);
            case SHOP_DAILY_DEALS_FREE_CHEST -> new DailyDealsFreeChestRoutine(profile, type);
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
            case LABYRINTH_RAID -> new LabyrinthRaidRoutine(profile, type);
            case TREK_SUPPLIES -> new TundraTrekRoutine(profile, type);
            case TREK_AUTOMATION -> new TundraTrekAutoRoutine(profile, type);

            // Pets
            case PET_ADVENTURE -> new PetAdventureChestRoutine(profile, type);
            case PET_SKILLS -> new PetSkillsRoutine(profile, type);
            case PET_SKILL_STAMINA -> new PetSkillsRoutine(profile, type, PetSkillsRoutine.PetSkill.STAMINA);
            case PET_SKILL_TREASURE -> new PetSkillsRoutine(profile, type, PetSkillsRoutine.PetSkill.TREASURE);
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

            // Bearguard
            case EVENT_CRYPTID_HOST -> new CryptidHostingRoutine(profile, type);
            case CHAT_CAPTURE -> new ChatCaptureRoutine(profile, type);
            case RESOURCE_STOCKPILE_SCAN -> new ResourceStockpileRoutine(profile, type);
            case TIMER_SWEEP -> new TimerSweepRoutine(profile, type);

            default -> null;
        };
    }
}
