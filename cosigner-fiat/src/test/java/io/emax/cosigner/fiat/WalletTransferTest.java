package io.emax.cosigner.fiat;

import io.emax.cosigner.api.currency.Wallet;
import io.emax.cosigner.common.ByteUtilities;
import io.emax.cosigner.common.Json;
import io.emax.cosigner.ethereum.EthereumResource;
import io.emax.cosigner.ethereum.gethrpc.RawTransaction;
import io.emax.cosigner.ethereum.stubrpc.EthereumTestRpc;
import io.emax.cosigner.fiat.gethrpc.fiatcontract.FiatContractInterface;
import io.emax.cosigner.fiat.gethrpc.fiatcontract.v1.FiatContractV1;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class WalletTransferTest {

  @Test
  public void createTransfer() {
    EthereumResource.getResource().setEthereumRpc(new EthereumTestRpc());
    Wallet wallet = new FiatWallet(new FiatConfiguration("EUR"));

    String userKey1 = "deadbeef1";
    String userKey2 = "deadbeef2";
    String userAddress1 = wallet.createAddress(userKey1);
    String userAddress2 = wallet.createAddress(userKey2);

    String msigAddressTest = wallet.getMultiSigAddress(Arrays.asList(userAddress1), userKey1);
    org.junit.Assert.assertEquals(msigAddressTest, userAddress1);

    msigAddressTest = wallet.getMultiSigAddress(Arrays.asList(userAddress2), userKey2);
    org.junit.Assert.assertEquals(msigAddressTest, userAddress2);

    Wallet.Recipient recipient = new Wallet.Recipient();
    recipient.setAmount(new BigDecimal("10.00"));
    recipient.setRecipientAddress(userAddress2);
    String tx = wallet.createTransaction(Arrays.asList(userAddress1), Arrays.asList(recipient));
    System.out.println("Transfer from " + userAddress1 + " to " + userAddress2 + ": " + tx);

    // Test parsing the tx.
    FiatContractInterface contract = new FiatContractV1();
    RawTransaction rawTx = RawTransaction.parseBytes(ByteUtilities.toByteArray(tx));
    Map<String, List<String>> transferData = contract.getContractParameters()
        .parseTransfer(ByteUtilities.toHexString(rawTx.getData().getDecodedContents()));
    System.out.println(Json.stringifyObject(Map.class, transferData));

    // Sign transfer
    String signedTx = wallet.signTransaction(tx, userAddress1, userKey1);
    System.out.println("Signed tx: " + signedTx);

    // Re-parsing the tx.
    contract = new FiatContractV1();
    rawTx = RawTransaction.parseBytes(ByteUtilities.toByteArray(signedTx));
    transferData = contract.getContractParameters()
        .parseTransfer(ByteUtilities.toHexString(rawTx.getData().getDecodedContents()));
    System.out.println(Json.stringifyObject(Map.class, transferData));

    // Sign transfer
    signedTx = wallet.signTransaction(tx, userAddress1);
    System.out.println("Signed tx: " + signedTx);

    // Re-parsing the tx.
    contract = new FiatContractV1();
    rawTx = RawTransaction.parseBytes(ByteUtilities.toByteArray(signedTx));
    transferData = contract.getContractParameters()
        .parseTransfer(ByteUtilities.toHexString(rawTx.getData().getDecodedContents()));
    System.out.println(Json.stringifyObject(Map.class, transferData));

    // Send the tx
    String txid = wallet.sendTransaction(signedTx);
    System.out.println("Transaction ID: " + txid);

  }

}
