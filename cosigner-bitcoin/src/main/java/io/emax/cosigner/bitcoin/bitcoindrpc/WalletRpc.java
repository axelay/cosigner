package io.emax.cosigner.bitcoin.bitcoindrpc;

import com.googlecode.jsonrpc4j.JsonRpcMethod;

import java.math.BigDecimal;
import java.util.Map;


/**
 * Wallet RPCs.
 * 
 * <p>Note: the wallet RPCs are only available if Bitcoin Core was built with wallet support, which
 * is the default.
 * 
 * <p>https://bitcoin.org/en/developer-reference
 * 
 * @author dquintela
 */
public interface WalletRpc {
  /**
   * AddMultiSigAddress Requires wallet support. The addmultisigaddress RPC adds a P2SH multisig
   * address to the wallet.
   * 
   * @param nrequired The minimum (m) number of signatures required to spend this m-of-n multisig
   *        script
   * @param keys the full public keys, or addresses for known public keys
   * @param account The account name in which the address should be stored. Default is the default
   *        account, "" (an empty string)
   * @return The P2SH multisig address. The address will also be added to the wallet, and outputs
   *         paying that address will be tracked by the wallet
   * 
   */
  @JsonRpcMethod("addmultisigaddress")
  String addmultisigaddress(int nrequired, String[] keys, String account);

  /**
   * GetAddressesByAccount Requires wallet support.
   * 
   * <p>The getaddressesbyaccount RPC returns a list of every address assigned to a particular
   * account.
   * 
   */
  @JsonRpcMethod("getaddressesbyaccount")
  String[] getaddressesbyaccount(String accountName);

  /**
   * GetNewAddress Requires wallet support.
   * 
   * <p>The getnewaddress RPC returns a new Bitcoin address for receiving payments. If an account is
   * specified, payments received with the address will be credited to that account.
   * 
   * @param accountName The name of the account to put the address in. The default is the default
   *        account, an empty string ("")
   * @return A P2PKH address which has not previously been returned by this RPC. The address will be
   *         marked as a receiving address in the wallet. The address may already have been part of
   *         the keypool, so other RPCs such as the dumpwallet RPC may have disclosed it previously.
   *         If the wallet is unlocked, its keypool will also be filled to its max (by default, 100
   *         unused keys). If the wallet is locked and its keypool is empty, this RPC will fail
   */
  @JsonRpcMethod("getnewaddress")
  String getnewaddress(String accountName);

  /**
   * ImportAddress Requires wallet support. Added in Bitcoin Core 0.10.0.
   * 
   * <p>The importaddress RPC adds an address or pubkey script to the wallet without the associated
   * private key, allowing you to watch for transactions affecting that address or pubkey script
   * without being able to spend any of its outputs.
   * 
   * @param addressOrScript Either a P2PKH or P2SH address encoded in base58check, or a pubkey
   *        script encoded as hex
   * @param account The account into which to place the address or pubkey script An account name
   *        into which the address should be placed. Default is the default account, an empty
   *        string("")
   * @param rescan whether to rescan the block chain Set to true (the default) to rescan the entire
   *        local block database for transactions affecting any address or pubkey script in the
   *        wallet (including transaction affecting the newly-added address or pubkey script). Set
   *        to false to not rescan the block database (rescanning can be performed at any time by
   *        restarting Bitcoin Core with the -rescan command-line argument). Rescanning may take
   *        several minutes. Notes: if the address or pubkey script is already in the wallet, the
   *        block database will not be rescanned even if this parameter is set
   */
  @JsonRpcMethod("importaddress")
  void importaddress(String addressOrScript, String account, boolean rescan);

  /**
   * ListAccounts Requires wallet support.
   * 
   * <p>The listaccounts RPC lists accounts and their balances.
   * 
   * @param confirmations The minimum number of confirmations an externally-generated transaction
   *        must have before it is counted towards the balance. Transactions generated by this node
   *        are counted immediately. Typically, externally-generated transactions are payments to
   *        this wallet and transactions generated by this node are payments to other wallets. Use 0
   *        to count unconfirmed transactions. Default is 1
   * 
   * @param includeWatchOnly If set to true, include watch-only addresses in details and
   *        calculations as if they were regular addresses belonging to the wallet. If set to false
   *        (the default), treat watch-only addresses as if they didnâ€™t belong to this wallet
   * 
   * @return a list of accounts and their balances
   */
  @JsonRpcMethod("listaccounts")
  Map<String, BigDecimal> listaccounts(int confirmations, boolean includeWatchOnly);

  /**
   * ListUnspent Requires wallet support.
   * 
   * <p>The listunspent RPC returns an array of unspent transaction outputs belonging to this
   * wallet. Note: as of Bitcoin Core 0.10.0, outputs affecting watch-only addresses will be
   * returned; see the spendable field in the results described below.
   * 
   * @param minimumConfirmations the minimum number of confirmations an output must have The minimum
   *        number of confirmations the transaction containing an output must have in order to be
   *        returned. Use 0 to return outputs from unconfirmed transactions. Default is 1
   * @param maximumConfirmations the maximum number of confirmations an output may have The maximum
   *        number of confirmations the transaction containing an output may have in order to be
   *        returned. Default is 9999999 (~10 million)
   * @param addresses If present, only outputs which pay an address in this array will be returned
   *        the addresses an output must pay A P2PKH or P2SH address
   * @return the list of unspent outputs
   */
  @JsonRpcMethod("listunspent")
  Output[] listunspent(int minimumConfirmations, int maximumConfirmations, String[] addresses);

  /**
   * The listtransactions RPC returns the most recent transactions that affect the wallet.
   * 
   * @param account The name of an account to get transactinos from. Use an empty string (“”) to get
   *        transactions for the default account. Default is * to get transactions for all accounts
   * @param numberToReturn The number of the most recent transactions to list. Default is 10
   * @param numberToSkip The number of the most recent transactions which should not be returned.
   *        Allows for pagination of results. Default is 0
   * @param includeWatchOnly If set to true, include watch-only addresses in details and
   *        calculations as if they were regular addresses belonging to the wallet. If set to false
   *        (the default), treat watch-only addresses as if they didn’t belong to this wallet
   * @return A payment or internal accounting entry
   */
  @JsonRpcMethod("listtransactions")
  Payment[] listtransactions(String account, int numberToReturn, int numberToSkip,
      boolean includeWatchOnly);
}
