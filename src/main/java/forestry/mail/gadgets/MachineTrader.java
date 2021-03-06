/*******************************************************************************
 * Copyright (c) 2011-2014 SirSengir.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v3
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-3.0.txt
 * 
 * Various Contributors including, but not limited to:
 * SirSengir (original work), CovertJaguar, Player, Binnie, MysteriousAges
 ******************************************************************************/
package forestry.mail.gadgets;

import java.util.LinkedList;

import forestry.api.mail.MailAddress;
import forestry.core.utils.StringUtil;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTUtil;

import net.minecraftforge.common.util.ForgeDirection;

import buildcraft.api.gates.ITrigger;
import com.mojang.authlib.GameProfile;

import forestry.api.core.ForestryAPI;
import forestry.api.core.ISpecialInventory;
import forestry.api.mail.IStamps;
import forestry.api.mail.PostManager;
import forestry.core.EnumErrorCode;
import forestry.core.gadgets.TileBase;
import forestry.core.network.EntityNetData;
import forestry.core.network.GuiId;
import forestry.core.proxy.Proxies;
import forestry.core.utils.InventoryAdapter;
import forestry.core.utils.StackUtils;
import forestry.mail.TradeStation;
import forestry.plugins.PluginMail;
import org.apache.commons.lang3.StringUtils;

public class MachineTrader extends TileBase implements ISpecialInventory, ISidedInventory {

	@EntityNetData
	public String moniker = "";

	@Override
	public String getInventoryName() {
		return "mail.1";
	}

	@Override
	public void openGui(EntityPlayer player, TileBase tile) {
		if (isLinked())
			player.openGui(ForestryAPI.instance, GuiId.TraderGUI.ordinal(), worldObj, xCoord, yCoord, zCoord);
		else
			player.openGui(ForestryAPI.instance, GuiId.TraderNameGUI.ordinal(), worldObj, xCoord, yCoord, zCoord);
	}

	@Override
	public void onRemoval() {
		if (isLinked()) {
			MailAddress address = new MailAddress(moniker);
			PostManager.postRegistry.deleteTradeStation(worldObj, address);
		}
	}

	/* SAVING & LOADING */
	@Override
	public void writeToNBT(NBTTagCompound nbttagcompound) {
		super.writeToNBT(nbttagcompound);

		if (moniker != null) {
			nbttagcompound.setString("moniker", moniker);
		}
	}

	@Override
	public void readFromNBT(NBTTagCompound nbttagcompound) {
		super.readFromNBT(nbttagcompound);

		if (nbttagcompound.hasKey("moniker")) {
			moniker = nbttagcompound.getString("moniker");
		}
	}

	/* UPDATING */
	@Override
	public void updateServerSide() {

		if (worldObj.getTotalWorldTime() % 40 * 10 != 0)
			return;

		if (!hasPaperMin(0.0f) || !hasInputBufMin(0.0f)) {
			setErrorState(EnumErrorCode.NORESOURCE);
			return;
		}
		if (!hasPostageMin(2)) {
			setErrorState(EnumErrorCode.NOSTAMPS);
			return;
		}

		setErrorState(EnumErrorCode.OK);
	}

	/* STATE INFORMATION */

	public boolean isLinked() {
		return StringUtils.isNotBlank(getMoniker());
	}

	private float percentOccupied(int startSlot, int countSlots) {
		int max = 0;
		int avail = 0;

		IInventory tradeInventory = this.getOrCreateTradeInventory();
		for (int i = startSlot; i < startSlot + countSlots; i++) {
			max += 64;
			if (tradeInventory.getStackInSlot(i) == null)
				continue;
			avail += tradeInventory.getStackInSlot(i).stackSize;
		}

		return ((float) avail / (float) max);
	}

	public boolean hasPaperMin(float percentage) {
		return percentOccupied(TradeStation.SLOT_LETTERS_1, TradeStation.SLOT_LETTERS_COUNT) > percentage;
	}

	public boolean hasInputBufMin(float percentage) {
		return percentOccupied(TradeStation.SLOT_INPUTBUF_1, TradeStation.SLOT_BUFFER_COUNT) > percentage;
	}

	public boolean hasOutputBufMin(float percentage) {
		return percentOccupied(TradeStation.SLOT_OUTPUTBUF_1, TradeStation.SLOT_BUFFER_COUNT) > percentage;
	}

	public boolean hasPostageMin(int postage) {

		int posted = 0;

		IInventory tradeInventory = this.getOrCreateTradeInventory();
		for (int i = TradeStation.SLOT_STAMPS_1; i < TradeStation.SLOT_STAMPS_1 + TradeStation.SLOT_STAMPS_COUNT; i++) {
			ItemStack stamp = tradeInventory.getStackInSlot(i);
			if (stamp == null)
				continue;
			if (!(stamp.getItem() instanceof IStamps))
				continue;

			posted += ((IStamps) stamp.getItem()).getPostage(stamp).getValue() * stamp.stackSize;
		}

		return posted >= postage;
	}

	/* MONIKER */
	public String getMoniker() {
		return this.moniker;
	}

	public void setMoniker(String moniker) {

		if (Proxies.common.isSimulating(worldObj)) {
			MailAddress address = new MailAddress(moniker);

			if (!PostManager.postRegistry.isValidTradeAddress(worldObj, address)) {
				setErrorState(EnumErrorCode.NOTALPHANUMERIC);
				return;
			}

			if (!PostManager.postRegistry.isAvailableTradeAddress(worldObj, address)) {
				setErrorState(EnumErrorCode.NOTUNIQUE);
				return;
			}

			this.moniker = moniker;
			PostManager.postRegistry.getOrCreateTradeStation(worldObj, getOwnerProfile(), address);
			setErrorState(EnumErrorCode.OK);
			sendNetworkUpdate();
		} else
			this.moniker = moniker;
	}

	/* TRADING */
	public IInventory getOrCreateTradeInventory() {

		// Handle client side
		if (!Proxies.common.isSimulating(worldObj))
			return new InventoryAdapter(TradeStation.SLOT_SIZE, "INV");

		if (StringUtils.isBlank(this.moniker))
			return new InventoryAdapter(TradeStation.SLOT_SIZE, "INV");

		MailAddress address = new MailAddress(this.moniker);
		return PostManager.postRegistry.getOrCreateTradeStation(worldObj, getOwnerProfile(), address);
	}

	/* ISPECIALINVENTORY */
	@Override
	public int addItem(ItemStack stack, boolean doAdd, ForgeDirection from) {

		if (!this.isLinked())
			return 0;

		IInventory inventory = getOrCreateTradeInventory();
		ItemStack tradegood = inventory.getStackInSlot(TradeStation.SLOT_TRADEGOOD);

		// Special handling for paper
		if (stack.getItem() == Items.paper)
			// Handle paper as resource if its not the trade good or pumped in from above or below
			if ((tradegood != null && tradegood.getItem() != Items.paper) || from == ForgeDirection.DOWN || from == ForgeDirection.UP)
				return StackUtils.addToInventory(stack, inventory, doAdd, TradeStation.SLOT_LETTERS_1, TradeStation.SLOT_LETTERS_COUNT);

		// Special handling for stamps
		if (stack.getItem() instanceof IStamps)
			// Handle stamps as resource if its not the trade good or pumped in from above or below
			if ((tradegood != null && !(tradegood.getItem() instanceof IStamps)) || from == ForgeDirection.DOWN || from == ForgeDirection.UP)
				return StackUtils.addToInventory(stack, inventory, doAdd, TradeStation.SLOT_STAMPS_1, TradeStation.SLOT_STAMPS_COUNT);

		// Everything else
		if (tradegood == null)
			return 0;

		if (!tradegood.isItemEqual(stack))
			return 0;

		return StackUtils.addToInventory(stack, inventory, doAdd, TradeStation.SLOT_INPUTBUF_1, TradeStation.SLOT_BUFFER_COUNT);
	}

	@Override
	public ItemStack[] extractItem(boolean doRemove, ForgeDirection from, int maxItemCount) {

		if (!this.isLinked())
			return new ItemStack[0];

		ItemStack product = null;
		IInventory inventory = getOrCreateTradeInventory();
		for (int i = TradeStation.SLOT_OUTPUTBUF_1; i < TradeStation.SLOT_OUTPUTBUF_1 + TradeStation.SLOT_BUFFER_COUNT; i++) {
			ItemStack stackSlot = inventory.getStackInSlot(i);
			if (stackSlot == null)
				continue;
			if (stackSlot.stackSize <= 0)
				continue;

			product = inventory.decrStackSize(i, 1);
			break;
		}

		if (product != null)
			return new ItemStack[] { product };
		else
			return new ItemStack[0];
	}

	/* ISIDEDINVENTORY */
	private static int[] slotIndices;

	public int[] getSizeInventorySide(int side) {
		IInventory inventory = getOrCreateTradeInventory();
		if(slotIndices == null) {
			slotIndices = new int[inventory.getSizeInventory()];
			for(int i = 0; i < inventory.getSizeInventory(); i++)
				slotIndices[i] = i;
		}
		return slotIndices;
	}

	@Override
	protected boolean canTakeStackFromSide(int slotIndex, ItemStack itemstack, int side) {

		if(!super.canTakeStackFromSide(slotIndex, itemstack, side))
			return false;

		if(slotIndex >= TradeStation.SLOT_OUTPUTBUF_1 && slotIndex < TradeStation.SLOT_OUTPUTBUF_1 + TradeStation.SLOT_BUFFER_COUNT)
			return true;

		return false;
	}

	@Override
	protected boolean canPutStackFromSide(int slotIndex, ItemStack itemstack, int side) {
		if(!super.canPutStackFromSide(slotIndex, itemstack, side))
			return false;

		if (slotIndex >= TradeStation.SLOT_LETTERS_1 && slotIndex < TradeStation.SLOT_LETTERS_1 + TradeStation.SLOT_LETTERS_COUNT
				&& itemstack.getItem() == Items.paper) {
			return true;
		}

		if (slotIndex >= TradeStation.SLOT_STAMPS_1 && slotIndex < TradeStation.SLOT_STAMPS_COUNT) {
			return itemstack.getItem() instanceof IStamps;
		}

		if (slotIndex >= TradeStation.SLOT_INPUTBUF_1 && slotIndex < TradeStation.SLOT_BUFFER_COUNT) {
			IInventory inventory = getOrCreateTradeInventory();
			ItemStack tradegood = inventory.getStackInSlot(TradeStation.SLOT_TRADEGOOD);
			if(tradegood == null)
				return false;
			return StackUtils.isIdenticalItem(tradegood, itemstack);
		}

		return false;
	}

	/* IINVENTORY */
	@Override
	public int getSizeInventory() {
		return getOrCreateTradeInventory().getSizeInventory();
	}

	@Override
	public ItemStack getStackInSlot(int i) {
		return getOrCreateTradeInventory().getStackInSlot(i);
	}

	@Override
	public ItemStack decrStackSize(int i, int j) {
		return getOrCreateTradeInventory().decrStackSize(i, j);
	}

	@Override
	public void setInventorySlotContents(int i, ItemStack itemstack) {
		getOrCreateTradeInventory().setInventorySlotContents(i, itemstack);
	}

	@Override
	public ItemStack getStackInSlotOnClosing(int slot) {
		return getOrCreateTradeInventory().getStackInSlotOnClosing(slot);
	}

	@Override
	public void markDirty() {
		getOrCreateTradeInventory().markDirty();
	}

	@Override
	public int getInventoryStackLimit() {
		return getOrCreateTradeInventory().getInventoryStackLimit();
	}

	@Override
	public void openInventory() {
	}

	@Override
	public void closeInventory() {
	}

	/**
	 * TODO: just a specialsource workaround
	 */
	@Override
	public boolean isUseableByPlayer(EntityPlayer player) {
		return super.isUseableByPlayer(player);
	}

	/**
	 * TODO: just a specialsource workaround
	 */
	@Override
	public boolean hasCustomInventoryName() {
		return super.hasCustomInventoryName();
	}

	/**
	 * TODO: just a specialsource workaround
	 */
	@Override
	public boolean isItemValidForSlot(int slotIndex, ItemStack itemstack) {
		return super.isItemValidForSlot(slotIndex, itemstack);
	}

	/**
	 * TODO: just a specialsource workaround
	 */
	@Override
	public boolean canInsertItem(int i, ItemStack itemstack, int j) {
		return super.canInsertItem(i, itemstack, j);
	}

	/**
	 * TODO: just a specialsource workaround
	 */
	@Override
	public boolean canExtractItem(int i, ItemStack itemstack, int j) {
		return super.canExtractItem(i, itemstack, j);
	}

	/**
	 * TODO: just a specialsource workaround
	 */
	@Override
	public int[] getAccessibleSlotsFromSide(int side) {
		return super.getAccessibleSlotsFromSide(side);
	}

	/* ITRIGGERPROVIDER */
	@Override
	public LinkedList<ITrigger> getCustomTriggers() {
		LinkedList<ITrigger> res = new LinkedList<ITrigger>();
		res.add(PluginMail.lowPaper25);
		res.add(PluginMail.lowPaper10);
		res.add(PluginMail.lowInput25);
		res.add(PluginMail.lowInput10);
		res.add(PluginMail.lowPostage40);
		res.add(PluginMail.lowPostage20);
		res.add(PluginMail.highBuffer90);
		res.add(PluginMail.highBuffer75);
		return res;
	}

}
