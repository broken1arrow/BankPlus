package me.pulsi_.bankplus.economy;

import me.pulsi_.bankplus.BankPlus;
import me.pulsi_.bankplus.account.BPPlayer;
import me.pulsi_.bankplus.account.BPPlayerManager;
import me.pulsi_.bankplus.account.PlayerRegistry;
import me.pulsi_.bankplus.bankSystem.Bank;
import me.pulsi_.bankplus.bankSystem.BankGui;
import me.pulsi_.bankplus.bankSystem.BankRegistry;
import me.pulsi_.bankplus.bankSystem.BankUtils;
import me.pulsi_.bankplus.events.BPAfterTransactionEvent;
import me.pulsi_.bankplus.events.BPPreTransactionEvent;
import me.pulsi_.bankplus.listeners.playerChat.PlayerChatMethod;
import me.pulsi_.bankplus.mySQL.SQLPlayerManager;
import me.pulsi_.bankplus.utils.BPSets;
import me.pulsi_.bankplus.utils.BPUtils;
import me.pulsi_.bankplus.utils.texts.BPFormatter;
import me.pulsi_.bankplus.utils.texts.BPMessages;
import me.pulsi_.bankplus.values.Values;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public class BPEconomy {

    private static final Set<UUID> loadedPlayers = new HashSet<>();

    private final Bank originBank;
    private final HashMap<UUID, Holder> balances = new HashMap<>();
    private final Set<UUID> transactions = new HashSet<>();

    private final String moneyPath, interestPath, debtPath, levelPath;

    public BPEconomy(Bank originBank) {
        this.originBank = originBank;

        String bankName = originBank.getIdentifier();
        this.moneyPath = "banks." + bankName + ".money";
        this.interestPath = "banks." + bankName + ".interest";
        this.debtPath = "banks." + bankName + ".debt";
        this.levelPath = "banks." + bankName + ".level";
    }

    public static BPEconomy get(String bankName) {
        Bank bank = BankUtils.getBank(bankName);
        if (bank == null) return null;
        return bank.getBankEconomy();
    }

    public static List<BPEconomy> list() {
        List<BPEconomy> economies = new ArrayList<>();
        BankPlus pl = BankPlus.INSTANCE();
        if (pl == null) return economies;

        for (Bank bank : pl.getBankRegistry().getBanks().values()) economies.add(bank.getBankEconomy());
        return economies;
    }

    public static Set<String> nameList() {
        Set<String> economies = new HashSet<>();

        BankPlus pl = BankPlus.INSTANCE();
        if (pl == null) return economies;

        BankRegistry bankRegistry = pl.getBankRegistry();
        if (bankRegistry != null) economies = bankRegistry.getBanks().keySet();

        return economies;
    }

    /**
     * Get a set of all loaded player uuids.
     * A player is loaded even if one balance holder instance is loaded in a bank economy.
     * @return A set of UUIDs.
     */
    public static Set<UUID> getLoadedPlayers() {
        return new HashSet<>(loadedPlayers);
    }

    /**
     * Return a list with all player balances of all banks.
     *
     * @return A hashmap with the player name as KEY and the sum of all the player bank balances as VALUE.
     */
    public static LinkedHashMap<String, BigDecimal> getAllEconomiesBankBalances() {
        LinkedHashMap<String, BigDecimal> balances = new LinkedHashMap<>();
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            BigDecimal balance = BigDecimal.ZERO;
            for (String bankName : BankUtils.getAvailableBankNames(p)) balance = balance.add(get(bankName).getBankBalance(p));
            balances.put(p.getName(), balance);
        }
        return balances;
    }

    /**
     * Get the sum of player bank balances of all banks.
     *
     * @param p The player.
     */
    public static BigDecimal getBankBalancesSum(OfflinePlayer p) {
        BigDecimal amount = BigDecimal.ZERO;
        for (BPEconomy economy : list())
            amount = amount.add(economy.getBankBalance(p));
        return amount;
    }

    public Bank getOriginBank() {
        return originBank;
    }

    public boolean isPlayerBalanceLoaded(OfflinePlayer p) {
        return isPlayerBalanceLoaded(p.getUniqueId());
    }

    public boolean isPlayerBalanceLoaded(UUID uuid) {
        return balances.containsKey(uuid);
    }

    public Holder loadPlayerBalance(OfflinePlayer p) {
        return loadPlayerBalance(p.getUniqueId(), true);
    }

    public Holder loadPlayerBalance(UUID uuid) {
        return loadPlayerBalance(uuid, true);
    }

    public Holder loadPlayerBalance(OfflinePlayer p, boolean wasRegistered) {
        return loadPlayerBalance(p.getUniqueId(), wasRegistered);
    }

    public Holder loadPlayerBalance(UUID uuid, boolean wasRegistered) {
        loadedPlayers.add(uuid);
        if (isPlayerBalanceLoaded(uuid)) return balances.get(uuid);

        Holder holder = new Holder();

        BigDecimal debt, money, offlineInterest;
        int level;

        boolean useStartAmount = !wasRegistered && BankUtils.isMainBank(originBank);
        if (BankPlus.INSTANCE().getMySql().isConnected()) {
            SQLPlayerManager pManager = new SQLPlayerManager(uuid);

            String bankName = originBank.getIdentifier();
            debt = pManager.getDebt(bankName);
            money = useStartAmount ? Values.CONFIG.getStartAmount() : pManager.getMoney(bankName);
            offlineInterest = pManager.getOfflineInterest(bankName);
            level = pManager.getLevel(bankName);
        } else {
            FileConfiguration config = new BPPlayerManager(uuid).getPlayerConfig();

            debt = BPFormatter.getStyledBigDecimal(config.getString(debtPath));
            money = useStartAmount ? Values.CONFIG.getStartAmount() : BPFormatter.getStyledBigDecimal(config.getString(moneyPath));
            offlineInterest = BPFormatter.getStyledBigDecimal(config.getString(interestPath));
            level = Math.max(config.getInt(levelPath), 1);
        }

        holder.debt = debt;
        holder.money = money;
        holder.offlineInterest = offlineInterest;
        holder.bankLevel = level;

        balances.put(uuid, holder);
        return holder;
    }

    public void unloadPlayerBalance(UUID uuid) {
        balances.remove(uuid);

        boolean unloadPlayer = true;
        for (BPEconomy economy : list()) {
            if (!economy.isPlayerBalanceLoaded(uuid)) continue;

            unloadPlayer = false;
            break;
        }
        if (unloadPlayer) loadedPlayers.remove(uuid);
    }

    /**
     * Return a list with all player balances of that bank.
     *
     * @return A hashmap with the player name as KEY and the sum of all the player bank balances as VALUE.
     */
    public LinkedHashMap<String, BigDecimal> getAllBankBalances() {
        LinkedHashMap<String, BigDecimal> balances = new LinkedHashMap<>();
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) balances.put(p.getName(), getBankBalance(p));
        return balances;
    }

    /**
     * Get the player bank balance of the selected bank.
     *
     * @param p The player.
     */
    public BigDecimal getBankBalance(OfflinePlayer p) {
        return getBankBalance(p.getUniqueId());
    }

    /**
     * Get the player bank balance of the selected bank.
     *
     * @param uuid The UUID of the player.
     */
    public BigDecimal getBankBalance(UUID uuid) {
        Holder holder = isPlayerBalanceLoaded(uuid) ? balances.get(uuid) : loadPlayerBalance(uuid);
        return holder.money;
    }

    /**
     * Set the selected amount in the selected bank.
     *
     * @return Number representing the actual amount set.
     */
    public BigDecimal setBankBalance(OfflinePlayer p, BigDecimal amount) {
        return setBankBalance(p, amount, TransactionType.SET);
    }

    /**
     * Set the selected amount in the selected bank.
     *
     * @param ignoreEvents Choose if ignoring or not the bankplus transaction event.
     * @return Number representing the actual amount set.
     */
    public BigDecimal setBankBalance(OfflinePlayer p, BigDecimal amount, boolean ignoreEvents) {
        return setBankBalance(p, amount, ignoreEvents, TransactionType.SET);
    }

    /**
     * Set the selected amount in the selected bank.
     *
     * @param type Override the transaction type with the one you choose.
     * @return Number representing the actual amount set.
     */
    public BigDecimal setBankBalance(OfflinePlayer p, BigDecimal amount, TransactionType type) {
        return setBankBalance(p, amount, false, type);
    }

    private BigDecimal setBankBalance(OfflinePlayer p, BigDecimal amount, boolean ignoreEvents, TransactionType type) {
        BigDecimal result = new BigDecimal(0);
        if (startAndCheckTransaction(p)) return result;

        if (!ignoreEvents) {
            BPPreTransactionEvent event = preTransactionEvent(p, type, amount);
            if (event.isCancelled()) {
                endTransaction(p);
                return result;
            }

            amount = event.getTransactionAmount();
        }

        result = result.max(amount.min(BankUtils.getCapacity(originBank, p)));
        set(p, result);

        if (!ignoreEvents) afterTransactionEvent(p, type, amount);
        return result;
    }

    /**
     * Add the selected amount to the selected bank.
     *
     * @return Number representing the actual amount added.
     */
    public BigDecimal addBankBalance(OfflinePlayer p, BigDecimal amount) {
        return addBankBalance(p, amount, false, TransactionType.ADD);
    }

    /**
     * Add the selected amount to the selected bank.
     *
     * @param ignoreEvents Choose if ignoring or not the bankplus transaction event.
     * @return Number representing the actual amount added.
     */
    public BigDecimal addBankBalance(OfflinePlayer p, BigDecimal amount, boolean ignoreEvents) {
        return addBankBalance(p, amount, ignoreEvents, TransactionType.ADD);
    }

    /**
     * Add the selected amount to the selected bank.
     *
     * @param type Override the transaction type with the one you choose.
     * @return Number representing the actual amount added.
     */
    public BigDecimal addBankBalance(OfflinePlayer p, BigDecimal amount, TransactionType type) {
        return addBankBalance(p, amount, false, type);
    }

    private BigDecimal addBankBalance(OfflinePlayer p, BigDecimal amount, boolean ignoreEvents, TransactionType type) {
        BigDecimal result = new BigDecimal(0);
        if (startAndCheckTransaction(p)) return result;

        if (!ignoreEvents) {
            BPPreTransactionEvent event = preTransactionEvent(p, type, amount);
            if (event.isCancelled()) {
                endTransaction(p);
                return result;
            }

            amount = event.getTransactionAmount();
        }

        BigDecimal capacity = BankUtils.getCapacity(originBank, p), balance = getBankBalance(p);
        if (capacity.compareTo(BigDecimal.ZERO) <= 0) {
            result = amount;
            set(p, balance.add(result));
        } else {
            if (balance.add(amount).compareTo(capacity) < 0) {
                result = amount;
                set(p, balance.add(result));
            } else {
                result = capacity.subtract(balance).max(BigDecimal.ZERO);
                set(p, capacity);
            }
        }

        if (!ignoreEvents) afterTransactionEvent(p, type, amount);
        return result;
    }

    /**
     * Remove the selected amount.
     *
     * @return Number representing the actual amount removed.
     */
    public BigDecimal removeBankBalance(OfflinePlayer p, BigDecimal amount) {
        return removeBankBalance(p, amount, false, TransactionType.REMOVE);
    }

    /**
     * Remove the selected amount.
     *
     * @param ignoreEvents Choose if ignoring or not the bankplus transaction event.
     * @return Number representing the actual amount removed.
     */
    public BigDecimal removeBankBalance(OfflinePlayer p, BigDecimal amount, boolean ignoreEvents) {
        return removeBankBalance(p, amount, ignoreEvents, TransactionType.REMOVE);
    }

    /**
     * Remove the selected amount.
     *
     * @param type Override the transaction type with the one you choose.
     * @return Number representing the actual amount removed.
     */
    public BigDecimal removeBankBalance(OfflinePlayer p, BigDecimal amount, TransactionType type) {
        return removeBankBalance(p, amount, false, type);
    }

    private BigDecimal removeBankBalance(OfflinePlayer p, BigDecimal amount, boolean ignoreEvents, TransactionType type) {
        BigDecimal result = new BigDecimal(0);
        if (startAndCheckTransaction(p)) return result;

        if (!ignoreEvents) {
            BPPreTransactionEvent event = preTransactionEvent(p, type, amount);
            if (event.isCancelled()) {
                endTransaction(p);
                return result;
            }

            amount = event.getTransactionAmount();
        }

        BigDecimal balance = getBankBalance(p);
        if (balance.subtract(amount).compareTo(BigDecimal.ZERO) < 0) result = balance;
        else result = amount;

        set(p, balance.subtract(result));

        if (!ignoreEvents) afterTransactionEvent(p, type, result);
        return result;
    }

    /**
     * Get the offline interest earned from the selected player in the selected bank.
     *
     * @param p The player.
     * @return Offline interest.
     */
    public BigDecimal getOfflineInterest(OfflinePlayer p) {
        return getOfflineInterest(p.getUniqueId());
    }

    /**
     * Get the offline interest earned from the selected player in the selected bank.
     *
     * @param uuid The player UUID.
     * @return Offline interest.
     */
    public BigDecimal getOfflineInterest(UUID uuid) {
        Holder holder = isPlayerBalanceLoaded(uuid) ? balances.get(uuid) : loadPlayerBalance(uuid);
        return holder.offlineInterest;
    }

    /**
     * Set the offline interest to the selected amount in the selected bank.
     *
     * @param p      The player.
     * @param amount The new amount.
     */
    public void setOfflineInterest(OfflinePlayer p, BigDecimal amount) {
        if (startAndCheckTransaction(p)) return;

        Holder holder = isPlayerBalanceLoaded(p) ? balances.get(p.getUniqueId()) : loadPlayerBalance(p);
        holder.setOfflineInterest(amount);

        endTransaction(p);
    }

    /**
     * Get the player bank debt of the selected bank.
     *
     * @param p The player.
     */
    public BigDecimal getDebt(OfflinePlayer p) {
        return getDebt(p.getUniqueId());
    }

    /**
     * Get the player bank debt of the selected bank.
     *
     * @param uuid The player UUID.
     */
    public BigDecimal getDebt(UUID uuid) {
        Holder holder = isPlayerBalanceLoaded(uuid) ? balances.get(uuid) : loadPlayerBalance(uuid);
        return holder.debt;
    }

    /**
     * Set the player bank debt to the selected amount.
     *
     * @param p      The player.
     * @param amount The new debt amount.
     */
    public void setDebt(OfflinePlayer p, BigDecimal amount) {
        if (startAndCheckTransaction(p)) return;

        Holder holder = isPlayerBalanceLoaded(p) ? balances.get(p.getUniqueId()) : loadPlayerBalance(p);
        holder.setDebt(amount);

        endTransaction(p);
    }

    /**
     * Get the current bank level of that player.
     * @param p The player.
     * @return The current bank level.
     */
    public int getBankLevel(OfflinePlayer p) {
        return getBankLevel(p.getUniqueId());
    }

    /**
     * Get the current bank level of that player.
     * @param uuid The player UUID.
     * @return The current bank level.
     */
    public int getBankLevel(UUID uuid) {
        Holder holder = isPlayerBalanceLoaded(uuid) ? balances.get(uuid) : loadPlayerBalance(uuid);
        return holder.bankLevel;
    }

    public void setBankLevel(OfflinePlayer p, int level) {
        if (startAndCheckTransaction(p)) return;

        Holder holder = isPlayerBalanceLoaded(p) ? balances.get(p.getUniqueId()) : loadPlayerBalance(p);
        holder.setBankLevel(level);

        endTransaction(p);
    }

    /**
     * Method internally used to simplify the transactions.
     */
    private void set(OfflinePlayer p, BigDecimal amount) {
        Holder pHolder = isPlayerBalanceLoaded(p) ? balances.get(p.getUniqueId()) : loadPlayerBalance(p);
        pHolder.setMoney(amount);
        endTransaction(p);
    }

    public void deposit(Player p, BigDecimal amount) {
        if (BPUtils.isBankFull(p, originBank) || isInTransaction(p)) return;

        BPPreTransactionEvent event = preTransactionEvent(p, TransactionType.DEPOSIT, amount);
        if (event.isCancelled()) return;

        amount = event.getTransactionAmount();
        if (minimumAmount(p, amount, Values.CONFIG.getDepositMinimumAmount())) return;

        Economy economy = BankPlus.INSTANCE().getVaultEconomy();
        BigDecimal wallet = BigDecimal.valueOf(economy.getBalance(p));
        if (!BPUtils.checkPreRequisites(wallet, amount, p)) return;

        BigDecimal maxDeposit = Values.CONFIG.getMaxDepositAmount();
        if (maxDeposit.compareTo(BigDecimal.ZERO) > 0 && amount.compareTo(maxDeposit) >= 0) amount = maxDeposit;
        if (wallet.doubleValue() < amount.doubleValue()) amount = wallet;

        BigDecimal capacity = BankUtils.getCapacity(originBank, p).subtract(getBankBalance(p)), finalAmount = amount.min(capacity);

        BigDecimal depositTaxes = Values.CONFIG.getDepositTaxes(), taxes = BigDecimal.ZERO;
        if (depositTaxes.compareTo(BigDecimal.ZERO) > 0 && !p.hasPermission("bankplus.deposit.bypass-taxes")) {
            if (amount.compareTo(capacity) <= 0) taxes = finalAmount.multiply(depositTaxes.divide(BigDecimal.valueOf(100)));
            else {
                // Makes it able to fill the bank if a higher number is used.
                taxes = capacity.multiply(depositTaxes).divide(BigDecimal.valueOf(100), RoundingMode.DOWN);
                taxes = taxes.min(finalAmount).max(BigDecimal.ZERO);
                finalAmount = finalAmount.add(taxes);
            }
        }

        BigDecimal actualDepositingMoney = finalAmount.subtract(taxes);

        EconomyResponse depositResponse = economy.withdrawPlayer(p, finalAmount.doubleValue());
        if (BPUtils.hasFailed(p, depositResponse)) return;

        addBankBalance(p, actualDepositingMoney, true);
        BPMessages.send(p, "Success-Deposit", BPUtils.placeValues(p, actualDepositingMoney), BPUtils.placeValues(taxes, "taxes"));
        BPUtils.playSound("DEPOSIT", p);

        afterTransactionEvent(p, TransactionType.DEPOSIT, amount);
    }

    public void withdraw(Player p, BigDecimal amount) {
        if (isInTransaction(p)) return;

        BPPreTransactionEvent event = preTransactionEvent(p, TransactionType.WITHDRAW, amount);
        if (event.isCancelled()) return;

        amount = event.getTransactionAmount();
        if (minimumAmount(p, amount, Values.CONFIG.getWithdrawMinimumAmount())) return;

        BigDecimal bankBal = getBankBalance(p);
        if (!BPUtils.checkPreRequisites(bankBal, amount, p)) return;

        BigDecimal maxWithdraw = Values.CONFIG.getMaxWithdrawAmount();
        if (maxWithdraw.compareTo(BigDecimal.ZERO) > 0 && amount.compareTo(maxWithdraw) >= 0) amount = maxWithdraw;
        if (bankBal.compareTo(amount) < 0) amount = bankBal;

        BigDecimal withdrawTaxes = Values.CONFIG.getWithdrawTaxes(), taxes = BigDecimal.ZERO;
        if (withdrawTaxes.compareTo(BigDecimal.ZERO) > 0 && !p.hasPermission("bankplus.withdraw.bypass-taxes")) {
            taxes = amount.multiply(withdrawTaxes).divide(BigDecimal.valueOf(100), RoundingMode.DOWN);
            taxes = taxes.min(amount).max(BigDecimal.ZERO);
        }

        EconomyResponse withdrawResponse = BankPlus.INSTANCE().getVaultEconomy().depositPlayer(p, amount.subtract(taxes).doubleValue());
        if (BPUtils.hasFailed(p, withdrawResponse)) return;

        removeBankBalance(p, amount, true);
        BPMessages.send(p, "Success-Withdraw", BPUtils.placeValues(p, amount.subtract(taxes)), BPUtils.placeValues(taxes, "taxes"));
        BPUtils.playSound("WITHDRAW", p);

        afterTransactionEvent(p, TransactionType.WITHDRAW, amount);
    }

    /**
     * Initialize the custom withdraw task for the selected player (Withdraw through chat).
     * @param p The player.
     */
    public void customDeposit(Player p) {
        if (Values.MESSAGES.isTitleCustomAmountEnabled()) BPUtils.sendTitle(Values.MESSAGES.getCustomDepositTitle(), p);

        BPMessages.send(p, "Chat-Deposit");
        BPSets.addPlayerToDeposit(p);
        p.closeInventory();

        BPPlayer pl = PlayerRegistry.get(p);
        BankGui bankGui = getOriginBank().getBankGui();
        pl.setOpenedBankGui(bankGui);

        pl.setClosingTask(Bukkit.getScheduler().runTaskLater(BankPlus.INSTANCE(), () -> {
            PlayerChatMethod.reopenBank(p, bankGui);
            BPMessages.send(p, "Chat-Time-Expired");
        }, Values.CONFIG.getChatExitTime() * 20L));
    }

    /**
     * Initialize the custom deposit task for the selected player (Deposit through chat).
     * @param p The player.
     */
    public void customWithdraw(Player p) {
        if (Values.MESSAGES.isTitleCustomAmountEnabled()) BPUtils.sendTitle(Values.MESSAGES.getCustomWithdrawTitle(), p);

        BPMessages.send(p, "Chat-Withdraw");
        BPSets.addPlayerToWithdraw(p);
        p.closeInventory();

        BPPlayer pl = PlayerRegistry.get(p);
        BankGui bankGui = getOriginBank().getBankGui();
        pl.setOpenedBankGui(bankGui);

        pl.setClosingTask(Bukkit.getScheduler().runTaskLater(BankPlus.INSTANCE(), () -> {
            PlayerChatMethod.reopenBank(p, bankGui);
            BPMessages.send(p, "Chat-Time-Expired");
        }, Values.CONFIG.getChatExitTime() * 20L));
    }

    /**
     * Method used to execute the pay transaction.
     *
     * @param from     The player that will give the money.
     * @param to       The player that will receive your money.
     * @param amount   How much money you want to pay.
     * @param toBank   The bank where the money will be added.
     */
    public void pay(Player from, Player to, BigDecimal amount, Bank toBank) {
        if (isInTransaction(from)) return;

        BigDecimal senderBalance = getBankBalance(from);
        // Check if the sender has at least more than 0 money
        if (senderBalance.compareTo(amount) < 0) {
            BPMessages.send(from, "Insufficient-Money");
            return;
        }

        BPEconomy toEconomy = toBank.getBankEconomy();
        // Check if the receiver of the payment has the bank full
        if (toEconomy.getBankBalance(to).compareTo(BankUtils.getCapacity(toBank, to)) >= 0) {
            BPMessages.send(from, "Bank-Full", "%player%$" + to.getName());
            return;
        }

        BigDecimal added = toEconomy.addBankBalance(to, amount, TransactionType.PAY), extra = amount.subtract(added);
        BPMessages.send(to, "Payment-Received", BPUtils.placeValues(from, added));

        BigDecimal removed = removeBankBalance(from, amount.subtract(extra), TransactionType.PAY);
        BPMessages.send(from, "Payment-Sent", BPUtils.placeValues(to, removed));
    }

    private boolean minimumAmount(Player p, BigDecimal amount, BigDecimal minimum) {
        if (amount.compareTo(minimum) < 0) {
            BPMessages.send(p, "Minimum-Number", "%min%$" + minimum);
            return true;
        }
        return false;
    }

    private BPPreTransactionEvent preTransactionEvent(OfflinePlayer p, TransactionType type, BigDecimal amount) {
        BPPreTransactionEvent event = new BPPreTransactionEvent(
                p, type, getBankBalance(p), BankPlus.INSTANCE().getVaultEconomy().getBalance(p), amount, originBank.getIdentifier()
        );
        BPUtils.callEvent(event);
        return event;
    }

    private void afterTransactionEvent(OfflinePlayer p, TransactionType type, BigDecimal amount) {
        BPAfterTransactionEvent event = new BPAfterTransactionEvent(
                p, type, getBankBalance(p), BankPlus.INSTANCE().getVaultEconomy().getBalance(p), amount, originBank.getIdentifier()
        );
        BPUtils.callEvent(event);
    }

    private boolean startAndCheckTransaction(OfflinePlayer p) {
        if (transactions.contains(p.getUniqueId())) return true;
        transactions.add(p.getUniqueId());
        return false;
    }

    private boolean isInTransaction(OfflinePlayer p) {
        return transactions.contains(p.getUniqueId());
    }

    private void endTransaction(OfflinePlayer p) {
        transactions.remove(p.getUniqueId());
    }

    private static class Holder {
        private BigDecimal money = BigDecimal.ZERO, offlineInterest = BigDecimal.ZERO, debt = BigDecimal.ZERO;
        private int bankLevel = 1;

        public void setMoney(BigDecimal money) {
            this.money = BPFormatter.getStyledBigDecimal(money).max(BigDecimal.ZERO);
        }

        public void setOfflineInterest(BigDecimal offlineInterest) {
            this.offlineInterest = BPFormatter.getStyledBigDecimal(offlineInterest).max(BigDecimal.ZERO);
        }

        public void setDebt(BigDecimal debt) {
            this.debt = BPFormatter.getStyledBigDecimal(debt).max(BigDecimal.ZERO);
        }

        public void setBankLevel(int bankLevel) {
            this.bankLevel = Math.max(1, bankLevel);
        }
    }
}