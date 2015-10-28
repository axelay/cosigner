package io.emax.cosigner.ethereum;

import io.emax.cosigner.api.currency.Wallet.TransactionDetails;

import rx.Observable;
import rx.Subscription;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class EthereumMonitor implements io.emax.cosigner.api.currency.Monitor {
  private HashSet<String> monitoredAddresses = new HashSet<>();
  private HashMap<String, String> accountBalances = new HashMap<>();
  private HashSet<TransactionDetails> accountTransactions = new HashSet<>();
  private HashSet<TransactionDetails> newAccountTransactions = new HashSet<>();

  private Observable<Map<String, String>> observableBalances =
      Observable.interval(1, TimeUnit.MINUTES).map(tick -> accountBalances);

  private Observable<Set<TransactionDetails>> observableTransactions =
      Observable.interval(1, TimeUnit.MINUTES).map(tick -> {
        HashSet<TransactionDetails> txs = new HashSet<>();
        txs.addAll(newAccountTransactions);
        newAccountTransactions.clear();
        return txs;
      });

  private Subscription balanceSubscription =
      Observable.interval(30, TimeUnit.SECONDS).map(tick -> updateBalances()).subscribe();

  private EthereumWallet wallet;

  public EthereumMonitor() {
    wallet = new EthereumWallet();
  }

  private boolean updateBalances() {
    monitoredAddresses.forEach(address -> {
      String currentBalance = wallet.getBalance(address);
      accountBalances.put(address, currentBalance);
    });

    updateTransactions();
    return true;
  }

  private boolean updateTransactions() {
    HashSet<TransactionDetails> details = new HashSet<>();
    monitoredAddresses.forEach(address -> {
      Arrays.asList(wallet.getTransactions(address, 100, 0)).forEach(tx -> {
        details.add(tx);
      });
    });

    // Remove the intersection
    details.removeAll(accountTransactions);
    accountTransactions.addAll(details);
    newAccountTransactions.addAll(details);

    return true;
  }

  @Override
  public void addAddresses(Iterable<String> addresses) {
    addresses.forEach(address -> monitoredAddresses.add(address));
  }

  @Override
  public void removeAddresses(Iterable<String> addresses) {
    addresses.forEach(monitoredAddresses::remove);
  }

  @Override
  public Iterable<String> listAddresses() {
    LinkedList<String> addresses = new LinkedList<>();
    monitoredAddresses.forEach(address -> addresses.add(address));
    return null;
  }

  @Override
  public Map<String, String> getBalances() {
    return accountBalances;
  }

  @Override
  public Observable<Map<String, String>> getObservableBalances() {
    return observableBalances;
  }

  @Override
  public Set<TransactionDetails> getTransactions() {
    return accountTransactions;
  }

  @Override
  public Observable<Set<TransactionDetails>> getObservableTransactions() {
    return observableTransactions;
  }

  @Override
  public io.emax.cosigner.api.currency.Monitor createNewMonitor() {
    return new EthereumMonitor();
  }

  @Override
  public void destroyMonitor() {
    if (balanceSubscription != null) {
      balanceSubscription.unsubscribe();
    }
  }
}