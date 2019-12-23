package com.palmergames.bukkit.towny.war.siegewar.playeractions;

import com.palmergames.bukkit.towny.*;
import com.palmergames.bukkit.towny.exceptions.EconomyException;
import com.palmergames.bukkit.towny.exceptions.TownyException;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.permissions.PermissionNodes;
import com.palmergames.bukkit.towny.war.siegewar.enums.SiegeStatus;
import com.palmergames.bukkit.towny.war.siegewar.locations.Siege;
import com.palmergames.bukkit.towny.war.siegewar.locations.SiegeZone;
import com.palmergames.bukkit.towny.war.siegewar.utils.SiegeWarBlockUtil;
import com.palmergames.util.TimeMgmt;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.List;

/**
 * This class is responsible for processing requests to start siege attacks
 *
 * @author Goosius
 */
public class AttackTown {

	/**
	 * Process an attack town request
	 *
	 * This method does some final checks and if they pass, the attack is initiated.
	 *
	 * @param player the player who placed the attack banner
	 * @param block the attack banner 
	 * @param nearbyTownBlocks any nearby town block (n,s,e, or w)
	 * @param event the place block event
	 */
    public static void processAttackTownRequest(Player player,
                                                Block block,
                                                List<TownBlock> nearbyTownBlocks,
                                                BlockPlaceEvent event) {

        try {
			TownyUniverse universe = TownyUniverse.getInstance();
            Resident attackingResident = universe.getDataSource().getResident(player.getName());
            if(!attackingResident.hasTown())
                throw new TownyException(TownySettings.getLangString("msg_err_siege_war_action_not_a_town_member"));

            Town townOfAttackingResident = attackingResident.getTown();
            if(!townOfAttackingResident.hasNation())
				throw new TownyException(TownySettings.getLangString("msg_err_siege_war_action_not_a_nation_member"));

			if (nearbyTownBlocks.size() > 1)
				throw new TownyException(TownySettings.getLangString("msg_err_siege_war_incorrect_town_block_facing"));

			Town defendingTown = nearbyTownBlocks.get(0).getTown();
            if(townOfAttackingResident == defendingTown)
                throw new TownyException(TownySettings.getLangString("msg_err_siege_war_cannot_attack_own_town"));

            Nation nationOfAttackingPlayer= townOfAttackingResident.getNation();
            if (defendingTown.hasNation()) {
                Nation nationOfDefendingTown = defendingTown.getNation();

                if(nationOfAttackingPlayer == nationOfDefendingTown)
                    throw new TownyException(TownySettings.getLangString("msg_err_siege_war_cannot_attack_town_in_own_nation"));

                if (!nationOfAttackingPlayer.hasEnemy(nationOfDefendingTown))
                    throw new TownyException(TownySettings.getLangString("msg_err_siege_war_cannot_attack_non_enemy_nation"));
            }
            
            if (!universe.getPermissionSource().testPermission(player, PermissionNodes.TOWNY_COMMAND_NATION_SIEGE_ATTACK.getNode()))
                throw new TownyException(TownySettings.getLangString("msg_err_command_disable"));

            if (nationOfAttackingPlayer.isNationAttackingTown(defendingTown))
                throw new TownyException(TownySettings.getLangString("msg_err_siege_war_nation_already_attacking_town"));

            if (defendingTown.isSiegeImmunityActive()) {
                throw new TownyException(TownySettings.getLangString("msg_err_siege_war_cannot_attack_siege_immunity"));
            }

            if (TownySettings.isUsingEconomy()) {
                double initialSiegeCost =
                        TownySettings.getWarSiegeAttackerCostUpFrontPerPlot()
                                * defendingTown.getTownBlocks().size();

                if (nationOfAttackingPlayer.canPayFromHoldings(initialSiegeCost))
                    //Deduct upfront cost
                    nationOfAttackingPlayer.pay(initialSiegeCost, "Cost of Initiating an assault siege.");
                else {
                    throw new TownyException(TownySettings.getLangString("msg_err_no_money"));
                }
            }

            if (SiegeWarBlockUtil.doesBlockHaveANonAirBlockAboveIt(block))
                throw new TownyException(TownySettings.getLangString("msg_err_siege_war_banner_must_be_placed_above_ground"));
            
            if(defendingTown.isRuined())
                throw new TownyException(TownySettings.getLangString("msg_err_siege_war_cannot_attack_ruined_town"));

            if(!SiegeWarBlockUtil.isBannerToTownElevationDifferenceOk(block, nearbyTownBlocks.get(0))) {
				throw new TownyException(TownySettings.getLangString("msg_err_siege_war_cannot_place_banner_far_above_town"));
			}
            
            //Setup attack
            attackTown(player, block, nationOfAttackingPlayer, defendingTown);
        } catch (TownyException x) {
            TownyMessaging.sendErrorMsg(player, x.getMessage());
            event.setCancelled(true);
        } catch (EconomyException x) {
            event.setCancelled(true);
            TownyMessaging.sendErrorMsg(player, x.getMessage());
        }
    }


    private static void attackTown(Player player,
								  Block block,
                                  Nation attackingNation,
                                    Town defendingTown) throws TownyException {

		Siege siege;
		SiegeZone siegeZone;
		boolean newSiege;

		if (!defendingTown.hasSiege()) {
			newSiege = true;

			//Create Siege
			siege = new Siege(defendingTown);
			siege.setStatus(SiegeStatus.IN_PROGRESS);
			siege.setTownPlundered(false);
			siege.setTownInvaded(false);
			siege.setAttackerWinner(null);
			siege.setStartTime(System.currentTimeMillis());
			siege.setScheduledEndTime(
				(System.currentTimeMillis() +
					((long) (TownySettings.getWarSiegeMaxHoldoutTimeHours() * TimeMgmt.ONE_HOUR_IN_MILLIS))));
			siege.setActualEndTime(0);
			defendingTown.setSiege(siege);

		} else {
			//Get Siege
			newSiege = false;
			siege = defendingTown.getSiege();
		}

		//Create siege zone
		TownyUniverse universe = TownyUniverse.getInstance();
		universe.getDataSource().newSiegeZone(
			attackingNation.getName(),
			defendingTown.getName());
		siegeZone = universe.getDataSource().getSiegeZone(
			SiegeZone.generateName(
				attackingNation.getName(), 
				defendingTown.getName()));
		
		siegeZone.setFlagLocation(block.getLocation());
		siege.getSiegeZones().put(attackingNation, siegeZone);
		attackingNation.addSiegeZone(siegeZone);

		//Save siegezone, siege, nation, and town to DB
		universe.getDataSource().saveSiegeZone(siegeZone);
		universe.getDataSource().saveNation(attackingNation);
		universe.getDataSource().saveTown(defendingTown);
		universe.getDataSource().saveSiegeZoneList();

		//Send global message;
		if (newSiege) {
			if (siege.getDefendingTown().hasNation()) {
				TownyMessaging.sendGlobalMessage(String.format(
					TownySettings.getLangString("msg_siege_war_siege_started_nation_town"),
					TownyFormatter.getFormattedNationName(attackingNation),
					TownyFormatter.getFormattedNationName(defendingTown.getNation()),
					TownyFormatter.getFormattedTownName(defendingTown)
				));
			} else {
				TownyMessaging.sendGlobalMessage(String.format(
					TownySettings.getLangString("msg_siege_war_siege_started_neutral_town"),
					TownyFormatter.getFormattedNationName(attackingNation),
					TownyFormatter.getFormattedTownName(defendingTown)
				));
			}
		} else {
			TownyMessaging.sendGlobalMessage(String.format(
				TownySettings.getLangString("msg_siege_war_siege_joined"),
				TownyFormatter.getFormattedNationName(attackingNation),
				TownyFormatter.getFormattedTownName(defendingTown)
			));
		}

		if (TownySettings.isUsingEconomy()) {
			String moneyMessage = 
				String.format(
				TownySettings.getLangString("msg_siege_war_attack_money"),
					TownyEconomyHandler.getFormattedBalance(defendingTown.getSiegeCost()),
					TownyEconomyHandler.getFormattedBalance(defendingTown.getPlunderValue()));
			
			TownyMessaging.sendMessage(player, moneyMessage);
		}
    }

}
