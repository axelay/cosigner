package io.emax.cosigner.bitcoin;

import io.emax.cosigner.api.core.ServerStatus;
import io.emax.cosigner.api.currency.CurrencyAdmin;
import io.emax.cosigner.api.currency.Wallet;
import io.emax.cosigner.api.validation.Validatable;
import io.emax.cosigner.bitcoin.bitcoindrpc.BitcoindRpc;
import io.emax.cosigner.bitcoin.bitcoindrpc.MultiSig;
import io.emax.cosigner.bitcoin.bitcoindrpc.Outpoint;
import io.emax.cosigner.bitcoin.bitcoindrpc.OutpointDetails;
import io.emax.cosigner.bitcoin.bitcoindrpc.Output;
import io.emax.cosigner.bitcoin.bitcoindrpc.Payment;
import io.emax.cosigner.bitcoin.bitcoindrpc.Payment.PaymentCategory;
import io.emax.cosigner.bitcoin.bitcoindrpc.RawInput;
import io.emax.cosigner.bitcoin.bitcoindrpc.RawOutput;
import io.emax.cosigner.bitcoin.bitcoindrpc.RawTransaction;
import io.emax.cosigner.bitcoin.bitcoindrpc.SigHash;
import io.emax.cosigner.bitcoin.bitcoindrpc.SignedTransaction;
import io.emax.cosigner.bitcoin.common.BitcoinTools;
import io.emax.cosigner.common.ByteUtilities;
import io.emax.cosigner.common.crypto.Secp256k1;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BitcoinWallet implements Wallet, Validatable, CurrencyAdmin {
  private static final Logger LOGGER = LoggerFactory.getLogger(BitcoinWallet.class);
  private final BitcoindRpc bitcoindRpc = BitcoinResource.getResource().getBitcoindRpc();
  private static final String PUBKEY_PREFIX = "PK-";

  BitcoinConfiguration config;

  private final HashMap<String, String> multiSigRedeemScripts = new HashMap<>();

  private Thread multiSigSubscription = new Thread(() -> {
    //noinspection InfiniteLoopStatement
    while (true) {
      try {
        LOGGER.info("Scanning BTC multi-sig addresses");
        scanForAddresses();
        Thread.sleep(60000);
      } catch (Exception e) {
        LOGGER.debug("Multisig scan interrupted.");
      }
    }
  });

  private Thread rescanThread = new Thread(() -> {
    //noinspection InfiniteLoopStatement
    while (true) {
      try {
        try {
          LOGGER.debug("Initiating blockchain rescan...");
          byte[] key = Secp256k1.generatePrivateKey();
          String privateKey = BitcoinTools.encodePrivateKey(ByteUtilities.toHexString(key));
          String address = BitcoinTools.getPublicAddress(privateKey, true);
          bitcoindRpc.importaddress(address, "RESCAN", true);
        } catch (Exception e) {
          LOGGER.debug("Rescan thread interrupted, or import timed out (expected)", e);
        }
        Thread.sleep(config.getRescanTimer() * 60L * 60L * 1000L);
      } catch (Exception e) {
        LOGGER.debug("Rescan thread interrupted, or import timed out (expected)", e);
      }
    }
  });

  public BitcoinWallet(BitcoinConfiguration conf) {
    config = conf;

    if (!multiSigSubscription.isAlive()) {
      multiSigSubscription.setDaemon(true);
      multiSigSubscription.start();
    }

    if (!rescanThread.isAlive()) {
      rescanThread.setDaemon(true);
      rescanThread.start();
    }
  }

  @Override
  public String createAddress(String name) {
    return createAddress(name, 0);
  }

  @Override
  public String createAddress(String name, int skipNumber) {
    int rounds = 1 + skipNumber;
    String privateKey =
        BitcoinTools.getDeterministicPrivateKey(name, config.getServerPrivateKey(), rounds);
    String newAddress = BitcoinTools.getPublicAddress(privateKey, true);
    String pubKey = BitcoinTools.getPublicKey(privateKey);
    String internalName = PUBKEY_PREFIX + pubKey;

    String[] existingAddresses = bitcoindRpc.getaddressesbyaccount(internalName);
    boolean oldAddress = true;

    while (oldAddress && rounds <= config.getMaxDeterministicAddresses()) {
      oldAddress = false;
      for (String existingAddress : existingAddresses) {
        if (existingAddress.equalsIgnoreCase(newAddress)) {
          oldAddress = true;
          rounds++;
          privateKey =
              BitcoinTools.getDeterministicPrivateKey(name, config.getServerPrivateKey(), rounds);
          newAddress = BitcoinTools.getPublicAddress(privateKey, true);
          break;
        }
      }
    }
    bitcoindRpc.importaddress(newAddress, internalName, false);

    return newAddress;
  }

  @Override
  public boolean registerAddress(String address) {
    bitcoindRpc.importaddress(address, "", false);
    return true;
  }

  @Override
  public String generatePrivateKey() {
    String key = ByteUtilities.toHexString(Secp256k1.generatePrivateKey());
    return BitcoinTools.encodePrivateKey(key);
  }

  @Override
  public String createAddressFromKey(String key, boolean isPrivateKey) {
    return BitcoinTools.getPublicAddress(key, isPrivateKey);
  }

  @Override
  public String generatePublicKey(String privateKey) {
    return ByteUtilities.toHexString(BitcoinTools.getPublicKeyBytes(privateKey));
  }

  @Override
  public Iterable<String> getAddresses(String name) {
    // Hash the user's key so it's not stored in the wallet
    String internalName = BitcoinTools.encodeUserKey(name);

    String[] addresses = bitcoindRpc.getaddressesbyaccount(internalName);
    return Arrays.asList(addresses);
  }

  @Override
  public String getMultiSigAddress(Iterable<String> addresses, String name) {
    // Hash the user's key so it's not stored in the wallet
    String internalName = BitcoinTools.encodeUserKey(name);
    String newAddress = generateMultiSigAddress(addresses, name);
    bitcoindRpc.importaddress(newAddress, internalName, false);

    return newAddress;
  }

  private void scanForAddresses() {
    try {
      Map<String, BigDecimal> knownAccounts = bitcoindRpc.listaccounts(0, true);
      knownAccounts.keySet().forEach(account -> {
        // Look for any known PK/Single accounts and generate the matching multisig in memory
        Pattern pattern = Pattern.compile("^" + PUBKEY_PREFIX + "(.*)");
        Matcher matcher = pattern.matcher(account);
        if (matcher.matches()) {
          String pubKey = matcher.group(1);
          try {
            generateMultiSigAddress(Collections.singletonList(pubKey), null);
          } catch (Exception e) {
            LOGGER.info(account + " appears to be an invalid account - ignoring");
          }
        }
      });
    } catch (Exception e) {
      LOGGER.debug("No accounts found when scanning");
    }
  }

  private String generateMultiSigAddress(Iterable<String> addresses, String name) {
    LinkedList<String> multisigAddresses = new LinkedList<>();
    addresses.forEach(address -> {
      // Check if any of the addresses belong to the user
      int rounds = 1;
      String userPrivateKey =
          BitcoinTools.getDeterministicPrivateKey(name, config.getServerPrivateKey(), rounds);

      String userAddress = BitcoinTools.NOKEY;
      if (!userPrivateKey.equalsIgnoreCase(BitcoinTools.NOKEY)) {
        userAddress = BitcoinTools.getPublicAddress(userPrivateKey, true);

        while (!address.equalsIgnoreCase(userAddress) && rounds <= config
            .getMaxDeterministicAddresses()) {
          rounds++;
          userPrivateKey =
              BitcoinTools.getDeterministicPrivateKey(name, config.getServerPrivateKey(), rounds);
          userAddress = BitcoinTools.getPublicAddress(userPrivateKey, true);
        }
      }

      if (address.equalsIgnoreCase(userAddress)) {
        multisigAddresses.add(BitcoinTools.getPublicKey(userPrivateKey));
      } else {
        multisigAddresses.add(address);
      }
    });

    for (String account : config.getMultiSigAccounts()) {
      if (!account.isEmpty()) {
        multisigAddresses.add(account);
      }
    }

    for (String accountKey : config.getMultiSigKeys()) {
      if (!accountKey.isEmpty()) {
        multisigAddresses.add(BitcoinTools.getPublicKey(accountKey));
      }
    }

    String[] addressArray = new String[multisigAddresses.size()];
    MultiSig newAddress = bitcoindRpc
        .createmultisig(config.getMinSignatures(), multisigAddresses.toArray(addressArray));
    if (name != null && !name.isEmpty()) {
      // Bitcoind refuses to connect the address it has to the p2sh script even when provided.
      // Simplest to just load it, it still doesn't have the private keys.
      bitcoindRpc
          .addmultisigaddress(config.getMinSignatures(), multisigAddresses.toArray(addressArray),
              BitcoinTools.encodeUserKey(name));
    }

    multiSigRedeemScripts.put(newAddress.getAddress(), newAddress.getRedeemScript());

    return newAddress.getAddress();
  }

  @Override
  public String getBalance(String address) {
    BigDecimal balance = BigDecimal.ZERO;
    try {
      Output[] outputs = bitcoindRpc
          .listunspent(config.getMinConfirmations(), config.getMaxConfirmations(),
              new String[]{address});
      for (Output output : outputs) {
        balance = balance.add(output.getAmount());
      }
    } catch (Exception e) {
      LOGGER.debug(null, e);
    }
    return balance.toPlainString();
  }

  @Override
  public String createTransaction(Iterable<String> fromAddress, Iterable<Recipient> toAddress) {
    List<String> fromAddresses = new LinkedList<>();
    fromAddress.forEach(fromAddresses::add);
    String[] addresses = new String[fromAddresses.size()];
    Outpoint[] outputs = bitcoindRpc
        .listunspent(config.getMinConfirmations(), config.getMaxConfirmations(),
            fromAddresses.toArray(addresses));

    List<Outpoint> usedOutputs = new LinkedList<>();
    Map<String, BigDecimal> txnOutput = new HashMap<>();
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal subTotal = BigDecimal.ZERO;
    Iterator<Recipient> recipients = toAddress.iterator();
    Recipient recipient = recipients.next();
    boolean filledAllOutputs = false;
    for (Outpoint output : outputs) {
      total = total.add(output.getAmount());
      subTotal = subTotal.add(output.getAmount());
      usedOutputs.add(output);

      // Force tx amount > amount due so there should be enough to pay fees
      if (subTotal.compareTo(recipient.getAmount()) > 0) {
        LOGGER.debug("Recipient: " + recipient.getRecipientAddress());
        txnOutput.put(recipient.getRecipientAddress(), recipient.getAmount());
        subTotal = subTotal.subtract(recipient.getAmount());
        if (recipients.hasNext()) {
          recipient = recipients.next();
        } else {
          // 0.0001 BTC * 1000 Bytes suggested by spec
          int byteSize = 0;
          // inputs for normal TXs should only be ~181 bytes
          byteSize += usedOutputs.size() * 181;
          // outputs should be ~34 bytes
          byteSize += (txnOutput.size() + 1) * 34;
          // tx overhead should be ~10 bytes
          byteSize += 10;
          // round up to the nearest KB.
          LOGGER.debug("Estimated tx size: " + byteSize);
          byteSize = (int) Math.ceil(((double) byteSize) / 1000);
          BigDecimal fees =
              BigDecimal.valueOf((double) byteSize).multiply(BigDecimal.valueOf(0.0001));
          LOGGER.debug("Expecting fees of: " + fees.toPlainString());
          // Only set a change address if there's change.
          if (subTotal.compareTo(fees) > 0) {
            subTotal = subTotal.subtract(fees);
            LOGGER.debug("We have change: " + subTotal.toPlainString());
            txnOutput.put(fromAddress.iterator().next(), subTotal);
          }
          filledAllOutputs = true;
          break;
        }
      }
    }

    // We don't have enough to complete the transaction
    if (!filledAllOutputs) {
      return null;
    }

    RawTransaction rawTx = new RawTransaction();
    rawTx.setVersion(1);
    rawTx.setInputCount(usedOutputs.size());
    usedOutputs.forEach(input -> {
      RawInput rawInput = new RawInput();
      rawInput.setTxHash(input.getTransactionId());
      rawInput.setTxIndex((int) input.getOutputIndex());
      rawInput.setSequence(-1);
      rawTx.getInputs().add(rawInput);
    });
    rawTx.setOutputCount(txnOutput.size());
    txnOutput.forEach((address, amount) -> {
      RawOutput rawOutput = new RawOutput();
      rawOutput.setAmount(amount.multiply(BigDecimal.valueOf(100000000)).longValue());
      LOGGER.debug("Address: " + address);
      String decodedAddress = BitcoinTools.decodeAddress(address);
      LOGGER.debug("Decoded address: " + decodedAddress);
      byte[] addressBytes = ByteUtilities.toByteArray(decodedAddress);
      String scriptData = "";
      if (!BitcoinTools.isMultiSigAddress(address)) {
        // Regular address
        scriptData = "76a914";
        scriptData += ByteUtilities.toHexString(addressBytes);
        scriptData += "88ac";
      } else {
        // Multi-sig address
        scriptData = "a914";
        scriptData += ByteUtilities.toHexString(addressBytes);
        scriptData += "87";
      }
      rawOutput.setScript(scriptData);
      rawTx.getOutputs().add(rawOutput);
    });
    rawTx.setLockTime(0);

    return rawTx.encode();
  }

  @Override
  public Iterable<String> getSignersForTransaction(String transaction) {
    RawTransaction tx = RawTransaction.parse(transaction);
    LinkedList<String> addresses = new LinkedList<>();

    Outpoint[] outputs = bitcoindRpc
        .listunspent(config.getMinConfirmations(), config.getMaxConfirmations(), new String[]{});

    tx.getInputs().forEach(input -> {
      for (Outpoint output : outputs) {
        if (output.getTransactionId().equalsIgnoreCase(input.getTxHash())
            && output.getOutputIndex() == input.getTxIndex()) {

          String redeemScript = multiSigRedeemScripts.get(output.getAddress());
          Iterable<String> publicKeys = RawTransaction.decodeRedeemScript(redeemScript);
          publicKeys.forEach(key -> addresses.add(BitcoinTools.getPublicAddress(key, false)));
        }
      }
    });
    return addresses;
  }

  @Override
  public String signTransaction(String transaction, String address) {
    return signTransaction(transaction, address, null);
  }

  @Override
  public String signTransaction(String transaction, String address, String name) {
    LOGGER.debug("Attempting to sign a transaction");
    int rounds = 1;
    String privateKey;
    String userAddress;
    SignedTransaction signedTransaction;

    if (name != null) {
      LOGGER.debug("User key has value, trying to determine private key");
      privateKey =
          BitcoinTools.getDeterministicPrivateKey(name, config.getServerPrivateKey(), rounds);
      userAddress = BitcoinTools.getPublicAddress(privateKey, true);
      while (!(userAddress != null && userAddress.equalsIgnoreCase(address))
          && !generateMultiSigAddress(Collections.singletonList(userAddress), name)
          .equalsIgnoreCase(address) && rounds < config.getMaxDeterministicAddresses()) {
        rounds++;
        privateKey =
            BitcoinTools.getDeterministicPrivateKey(name, config.getServerPrivateKey(), rounds);
        userAddress = BitcoinTools.getPublicAddress(privateKey, true);
      }

      // If we hit max addresses/user bail out
      if (!(userAddress != null && userAddress.equalsIgnoreCase(address))
          && !generateMultiSigAddress(Collections.singletonList(userAddress), name)
          .equalsIgnoreCase(address)) {
        LOGGER.debug("Too many rounds, failed to sign");
        return transaction;
      }

      LOGGER.debug("We can sign for " + userAddress);

      // BTC TX is re-verified since we look up the outputs in getSigString
      signedTransaction = new SignedTransaction();
      Iterable<Iterable<String>> signatureData = getSigString(transaction, address);
      signatureData = signWithPrivateKey(signatureData, privateKey);
      signedTransaction.setTransaction(applySignature(transaction, address, signatureData));
    } else {
      try {
        LOGGER.debug("Asking bitcoind to sign...");
        signedTransaction =
            bitcoindRpc.signrawtransaction(transaction, new OutpointDetails[]{}, null, SigHash.ALL);
      } catch (Exception e) {
        signedTransaction = new SignedTransaction();
        signedTransaction.setTransaction(transaction);
      }

      for (String accountKey : config.getMultiSigKeys()) {
        if (!accountKey.isEmpty()) {
          transaction = signedTransaction.getTransaction();
          address = BitcoinTools.getPublicAddress(accountKey, true);
          signedTransaction = new SignedTransaction();
          Iterable<Iterable<String>> signatureData = getSigString(transaction, address);
          signatureData = signWithPrivateKey(signatureData, accountKey);
          signedTransaction.setTransaction(applySignature(transaction, address, signatureData));
        }
      }
    }

    return signedTransaction.getTransaction();
  }

  @Override
  public Iterable<Iterable<String>> getSigString(String transaction, String address) {
    LinkedList<Iterable<String>> signatureData = new LinkedList<>();
    Outpoint[] outputs = bitcoindRpc
        .listunspent(config.getMinConfirmations(), config.getMaxConfirmations(), new String[]{});

    RawTransaction rawTx = RawTransaction.parse(transaction);
    for (RawInput input : rawTx.getInputs()) {
      for (Outpoint output : outputs) {
        LOGGER.debug("Looking for outputs we can sign");
        if (output.getTransactionId().equalsIgnoreCase(input.getTxHash())
            && output.getOutputIndex() == input.getTxIndex()) {
          OutpointDetails outpoint = new OutpointDetails();
          outpoint.setTransactionId(output.getTransactionId());
          outpoint.setOutputIndex(output.getOutputIndex());
          outpoint.setScriptPubKey(output.getScriptPubKey());
          outpoint.setRedeemScript(multiSigRedeemScripts.get(output.getAddress()));

          if (output.getAddress().equalsIgnoreCase(address)) {
            RawTransaction signingTx = RawTransaction.stripInputScripts(rawTx);
            byte[] sigData;

            LOGGER.debug("Found an output, matching to inputs in the transaction");
            for (RawInput sigInput : signingTx.getInputs()) {
              if (sigInput.getTxHash().equalsIgnoreCase(outpoint.getTransactionId())
                  && sigInput.getTxIndex() == outpoint.getOutputIndex()) {
                // This is the input we're processing, fill it and sign it
                if (BitcoinTools.isMultiSigAddress(address)) {
                  sigInput.setScript(outpoint.getRedeemScript());
                } else {
                  sigInput.setScript(outpoint.getScriptPubKey());
                }

                byte[] hashTypeBytes =
                    ByteUtilities.stripLeadingNullBytes(BigInteger.valueOf(1).toByteArray());
                hashTypeBytes = ByteUtilities.leftPad(hashTypeBytes, 4, (byte) 0x00);
                hashTypeBytes = ByteUtilities.flipEndian(hashTypeBytes);
                String sigString = signingTx.encode() + ByteUtilities.toHexString(hashTypeBytes);
                LOGGER.debug("Signing: " + sigString);

                try {
                  sigData = ByteUtilities.toByteArray(sigString);
                  MessageDigest md = MessageDigest.getInstance("SHA-256");
                  sigData = md.digest(md.digest(sigData));
                  LinkedList<String> inputSigData = new LinkedList<>();
                  inputSigData.add(input.getTxHash());
                  inputSigData.add(Integer.toString(input.getTxIndex()));
                  inputSigData.add(output.getRedeemScript());
                  inputSigData.add(ByteUtilities.toHexString(sigData));
                  signatureData.add(inputSigData);
                } catch (Exception e) {
                  LOGGER.error(null, e);
                }
              }
            }
          }
        }
      }
    }
    return signatureData;
  }

  @Override
  public Iterable<Iterable<String>> signWithPrivateKey(Iterable<Iterable<String>> data,
      String privateKey) {
    final byte[] addressData = BitcoinTools.getPublicKeyBytes(privateKey);
    final byte[] privateKeyBytes =
        ByteUtilities.toByteArray(BitcoinTools.decodeAddress(privateKey));

    Iterator<Iterable<String>> signatureData = data.iterator();
    LinkedList<Iterable<String>> signatures = new LinkedList<>();
    while (signatureData.hasNext()) {
      Iterator<String> signatureEntry = signatureData.next().iterator();
      LinkedList<String> signatureResults = new LinkedList<>();
      // Hash
      signatureResults.add(signatureEntry.next());
      // Index
      signatureResults.add(signatureEntry.next());
      // Redeem Script
      signatureResults.add(signatureEntry.next());
      signatureResults.add(ByteUtilities.toHexString(addressData));
      // Sig string
      byte[] sigData = ByteUtilities.toByteArray(signatureEntry.next());

      byte[][] sigResults = Secp256k1.signTransaction(sigData, privateKeyBytes);
      // BIP62
      BigInteger lowSlimit =
          new BigInteger("007FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF5D576E7357A4501DDFE92F46681B20A0", 16);
      BigInteger ourSvalue = new BigInteger(1, sigResults[1]);
      while (ourSvalue.compareTo(lowSlimit) > 0) {
        sigResults = Secp256k1.signTransaction(sigData, privateKeyBytes);
        ourSvalue = new BigInteger(1, sigResults[1]);
      }
      StringBuilder signature = new StringBuilder();
      // Only want R & S, don't need V
      for (int i = 0; i < 2; i++) {
        byte[] sig = sigResults[i];
        signature.append("02");
        byte[] sigSize = BigInteger.valueOf(sig.length).toByteArray();
        sigSize = ByteUtilities.stripLeadingNullBytes(sigSize);
        signature.append(ByteUtilities.toHexString(sigSize));
        signature.append(ByteUtilities.toHexString(sig));
      }

      byte[] sigBytes = ByteUtilities.toByteArray(signature.toString());
      byte[] sigSize = BigInteger.valueOf(sigBytes.length).toByteArray();
      sigSize = ByteUtilities.stripLeadingNullBytes(sigSize);
      String signatureString = ByteUtilities.toHexString(sigSize) + signature.toString();
      signatureString = "30" + signatureString;

      signatureResults.add(signatureString);
      signatures.add(signatureResults);

    }
    return signatures;
  }

  @Override
  public String applySignature(String transaction, String address,
      Iterable<Iterable<String>> signatureData) {
    Iterator<Iterable<String>> signatures = signatureData.iterator();
    RawTransaction rawTx = RawTransaction.parse(transaction);
    while (signatures.hasNext()) {
      Iterable<String> signature = signatures.next();
      Iterator<String> sigDataIterator = signature.iterator();
      rawTx = RawTransaction.parse(transaction);
      String signedTxHash = sigDataIterator.next();
      String signedTxIndex = sigDataIterator.next();
      String signedTxRedeemScript = sigDataIterator.next();
      byte[] addressData = ByteUtilities.toByteArray(sigDataIterator.next());
      byte[] sigData = ByteUtilities.toByteArray(sigDataIterator.next());

      // Determine how we need to format the sig data
      if (BitcoinTools.isMultiSigAddress(address)) {
        for (RawInput signedInput : rawTx.getInputs()) {
          if (signedInput.getTxHash().equalsIgnoreCase(signedTxHash) && Integer
              .toString(signedInput.getTxIndex()).equalsIgnoreCase(signedTxIndex)) {
            // Merge the new signature with existing ones.
            signedInput.stripMultiSigRedeemScript(signedTxRedeemScript);

            String scriptData = signedInput.getScript();
            if (scriptData.isEmpty()) {
              scriptData += "00";
            }

            byte[] dataSize = RawTransaction.writeVariableStackInt(sigData.length + 1);
            scriptData += ByteUtilities.toHexString(dataSize);
            scriptData += ByteUtilities.toHexString(sigData);
            scriptData += "01";

            byte[] redeemScriptBytes = ByteUtilities.toByteArray(signedTxRedeemScript);
            dataSize = RawTransaction.writeVariableStackInt(redeemScriptBytes.length);
            scriptData += ByteUtilities.toHexString(dataSize);
            scriptData += ByteUtilities.toHexString(redeemScriptBytes);

            signedInput.setScript(scriptData);
            break;
          }
        }
      } else {
        for (RawInput signedInput : rawTx.getInputs()) {
          if (signedInput.getTxHash().equalsIgnoreCase(signedTxHash) && Integer
              .toString(signedInput.getTxIndex()).equalsIgnoreCase(signedTxIndex)) {

            // Sig then pubkey
            String scriptData = "";
            byte[] dataSize = RawTransaction.writeVariableStackInt(sigData.length + 1);
            scriptData += ByteUtilities.toHexString(dataSize);
            scriptData += ByteUtilities.toHexString(sigData);
            scriptData += "01"; // SIGHASH.ALL

            dataSize = RawTransaction.writeVariableStackInt(addressData.length);
            scriptData += ByteUtilities.toHexString(dataSize);
            scriptData += ByteUtilities.toHexString(addressData);

            signedInput.setScript(scriptData);
            break;
          }
        }
      }
      transaction = rawTx.encode();
    }
    return rawTx.encode();
  }

  @Override
  public String sendTransaction(String transaction) {
    if(transactionsEnabled) {
      return bitcoindRpc.sendrawtransaction(transaction, false);
    } else {
      return "Transactions temporarily disabled";
    }
  }

  @Override
  public TransactionDetails[] getTransactions(String address, int numberToReturn, int skipNumber) {
    LinkedList<TransactionDetails> txDetails = new LinkedList<>();
    int pageSize = 1000;
    int pageNumber = 0;
    while (txDetails.size() < (numberToReturn + skipNumber)) {
      Payment[] payments = bitcoindRpc.listtransactions("*", pageSize, pageNumber * pageSize, true);
      if (payments.length == 0) {
        break;
      }

      for (Payment payment : Arrays.asList(payments)) {
        // Lookup the txid and vout/vin based on the sign of the amount (+/-)
        // Determine the address involved
        try {
          String rawTx = bitcoindRpc.getrawtransaction(payment.getTxid());
          RawTransaction tx = RawTransaction.parse(rawTx);
          if (payment.getCategory() == PaymentCategory.receive) {
            // Paid to the account

            if (payment.getAddress().equalsIgnoreCase(address)) {
              TransactionDetails detail = new TransactionDetails();
              detail.setAmount(payment.getAmount().abs());
              detail.setTxDate(new Date(payment.getBlocktime().toInstant().toEpochMilli() * 1000L));

              Map txData = bitcoindRpc.gettransaction(payment.getTxid(), true);
              detail
                  .setConfirmed(config.getMinConfirmations() <= (int) txData.get("confirmations"));
              detail.setConfirmations((int) txData.get("confirmations"));
              detail.setMinConfirmations(config.getMinConfirmations());

              // Senders
              HashSet<String> senders = new HashSet<>();
              tx.getInputs().forEach(input -> {
                try {
                  String rawSenderTx = bitcoindRpc.getrawtransaction(input.getTxHash());
                  RawTransaction senderTx = RawTransaction.parse(rawSenderTx);
                  String script = senderTx.getOutputs().get(input.getTxIndex()).getScript();
                  String scriptAddress = RawTransaction.decodePubKeyScript(script);
                  senders.add(scriptAddress);
                } catch (Exception e) {
                  LOGGER.debug(null, e);
                  senders.add(null);
                }
              });
              detail.setFromAddress(senders.toArray(new String[senders.size()]));

              detail.setToAddress(new String[]{address});
              detail.setTxHash(payment.getTxid());

              txDetails.add(detail);
            }
          } else if (payment.getCategory() == PaymentCategory.send) {
            // Sent from the account
            tx.getInputs().forEach(input -> {
              String rawSenderTx = bitcoindRpc.getrawtransaction(input.getTxHash());
              RawTransaction senderTx = RawTransaction.parse(rawSenderTx);
              String script = senderTx.getOutputs().get(input.getTxIndex()).getScript();
              String scriptAddress = RawTransaction.decodePubKeyScript(script);

              if (scriptAddress != null && scriptAddress.equalsIgnoreCase(address)) {
                TransactionDetails detail = new TransactionDetails();

                detail
                    .setTxDate(new Date(payment.getBlocktime().toInstant().toEpochMilli() * 1000L));
                detail.setTxHash(payment.getTxid());
                detail.setAmount(payment.getAmount().abs());
                detail.setFromAddress(new String[]{address});
                detail.setToAddress(new String[]{payment.getAddress()});

                Map txData = bitcoindRpc.gettransaction(payment.getTxid(), true);
                detail.setConfirmed(
                    config.getMinConfirmations() <= (int) txData.get("confirmations"));
                detail.setConfirmations((int) txData.get("confirmations"));
                detail.setMinConfirmations(config.getMinConfirmations());

                txDetails.add(detail);
              }
            });
          }
        } catch (Exception e) {
          LOGGER.debug(null, e);
        }
      }

      LinkedList<TransactionDetails> removeThese = new LinkedList<>();
      for (TransactionDetails detail : txDetails) {
        boolean noMatch = false;
        for (String from : Arrays.asList(detail.getFromAddress())) {
          boolean subMatch = false;
          for (String to : Arrays.asList(detail.getToAddress())) {
            if (to.equalsIgnoreCase(from)) {
              subMatch = true;
              break;
            }
          }
          if (!subMatch) {
            noMatch = true;
            break;
          }
        }

        // If the from & to's match then it's just a return amount, simpler if we don't list it.
        if (!noMatch) {
          removeThese.add(detail);
        }
      }

      removeThese.forEach(txDetails::remove);

      pageNumber++;
    }

    for (int i = 0; i < skipNumber; i++) {
      txDetails.removeFirst();
    }
    while (txDetails.size() > numberToReturn) {
      txDetails.removeLast();
    }

    return txDetails.toArray(new TransactionDetails[txDetails.size()]);
  }

  @Override
  public TransactionDetails decodeRawTransaction(String transaction) {
    RawTransaction tx = RawTransaction.parse(transaction);
    Set<String> senders = new HashSet<>();
    tx.getInputs().forEach(input -> {
      String rawSenderTx = bitcoindRpc.getrawtransaction(input.getTxHash());
      RawTransaction senderTx = RawTransaction.parse(rawSenderTx);
      String script = senderTx.getOutputs().get(input.getTxIndex()).getScript();
      String scriptAddress = RawTransaction.decodePubKeyScript(script);
      senders.add(scriptAddress);
    });

    Set<String> recipients = new HashSet<>();
    List<Long> satoshis = new LinkedList<>();
    tx.getOutputs().forEach(output -> {
      String scriptAddress = RawTransaction.decodePubKeyScript(output.getScript());
      if (senders.contains(scriptAddress)) {
        // Skip if it's change returned.
        return;
      }
      recipients.add(scriptAddress);
      satoshis.add(output.getAmount());
    });

    BigDecimal totalAmount = BigDecimal.ZERO;
    for (long amount : satoshis) {
      totalAmount = totalAmount.add(BigDecimal.valueOf(amount));
    }
    totalAmount = totalAmount.divide(BigDecimal.valueOf(100000000), BigDecimal.ROUND_UNNECESSARY);

    TransactionDetails txDetails = new TransactionDetails();
    txDetails.setAmount(totalAmount);
    txDetails.setFromAddress(senders.toArray(new String[senders.size()]));
    txDetails.setToAddress(recipients.toArray(new String[recipients.size()]));
    return txDetails;
  }

  @Override
  public TransactionDetails getTransaction(String transactionId) {
    Map txData = bitcoindRpc.gettransaction(transactionId, true);

    TransactionDetails txDetail = new TransactionDetails();
    txDetail.setTxHash(txData.get("txid").toString());
    txDetail.setConfirmed(config.getMinConfirmations() <= (int) txData.get("confirmations"));
    txDetail.setAmount(new BigDecimal(txData.get("amount").toString()));
    txDetail.setTxDate(new Date(((int) txData.get("blocktime")) * 1000L));
    txDetail.setConfirmations((int) txData.get("confirmations"));
    txDetail.setMinConfirmations(config.getMinConfirmations());

    LinkedList<String> senders = new LinkedList<>();
    LinkedList<String> recipients = new LinkedList<>();
    ArrayList<Map<String, Object>> txd = (ArrayList<Map<String, Object>>) txData.get("details");
    txd.forEach((txdMap) -> {
      if (txdMap.get("category").toString().equalsIgnoreCase("send")) {
        senders.add(txdMap.get("address").toString());
      } else if (txdMap.get("category").toString().equalsIgnoreCase("receive")) {
        recipients.add(txdMap.get("address").toString());
      }
    });

    txDetail.setFromAddress(senders.toArray(new String[senders.size()]));
    txDetail.setToAddress(recipients.toArray(new String[recipients.size()]));

    return txDetail;
  }

  @Override
  public ServerStatus getWalletStatus() {
    try {
      bitcoindRpc.getblockchaininfo().getChain();
      return ServerStatus.CONNECTED;
    } catch (Exception e) {
      return ServerStatus.DISCONNECTED;
    }
  }

  @Override
  public Map<String, String> getConfiguration() {
    HashMap<String, String> configSummary = new HashMap<>();
    configSummary.put("Currency Symbol", config.getCurrencySymbol());
    configSummary.put("Bitcoind Connection", config.getDaemonConnectionString());
    configSummary.put("Minimum Signatures", ((Integer) config.getMinSignatures()).toString());
    configSummary.put("Minimum Confirmations", ((Integer) config.getMinConfirmations()).toString());
    configSummary.put("Rescan Timer", ((Integer) config.getRescanTimer()).toString());
    configSummary
        .put("Maximum Transaction Value", config.getMaxAmountPerTransaction().toPlainString());
    configSummary
        .put("Maximum Transaction Value Per Hour", config.getMaxAmountPerHour().toPlainString());
    configSummary
        .put("Maximum Transaction Value Per Day", config.getMaxAmountPerDay().toPlainString());
    return configSummary;
  }

  private boolean transactionsEnabled = true;

  @Override
  public void enableTransactions() {
    transactionsEnabled = true;
  }

  @Override
  public void disableTransactions() {
    transactionsEnabled = false;
  }

  @Override
  public boolean transactionsEnabled() {
    return transactionsEnabled;
  }
}
