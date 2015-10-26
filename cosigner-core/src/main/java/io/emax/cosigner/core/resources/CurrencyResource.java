package io.emax.cosigner.core.resources;

import io.emax.cosigner.core.currency.Common;
import io.emax.cosigner.core.currency.CurrencyParameters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/rs")
public class CurrencyResource {
  Logger logger = LoggerFactory.getLogger(CurrencyResource.class);

  /**
   * REST end-point for the {@link Common} currency methods.
   * 
   * @return Common.listCurrencies()
   */
  @GET
  @Path("/ListCurrencies")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getCurrencies() {
    logger.debug("[GetCurrencies:Request]");
    Response response = Response.ok(Common.listCurrencies()).build();
    logger.debug("[GetCurrencies:Response] " + response.toString());
    return response;
  }

  /**
   * REST end-point for the {@link Common} currency methods.
   * 
   * @param params JSON representation of {@link CurrencyParameters}
   * @return Common.getNewAddress()
   */
  @POST
  @Path("/GetNewAddress")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getNewAccount(String params) {
    logger.debug("[GetNewAccount:Request]");
    Response response = Response.ok(Common.getNewAddress(params)).build();
    logger.debug("[GetNewAccount:Response] " + response.toString());
    return response;
  }

  /**
   * REST end-point for the {@link Common} currency methods.
   * 
   * @param params JSON representation of {@link CurrencyParameters}
   * @return Common.listAllAddresses()
   */
  @POST
  @Path("/ListAllAddresses")
  @Produces(MediaType.APPLICATION_JSON)
  public Response listAllAccounts(String params) {
    logger.debug("[ListAllAccounts:Request]");
    Response response = Response.ok(Common.listAllAddresses(params)).build();
    logger.debug("[ListAllAccounts:Response] " + response.toString());
    return response;
  }

  /**
   * REST end-point for the {@link Common} currency methods.
   * 
   * @param params JSON representation of {@link CurrencyParameters}
   * @return Common.listTransactions()
   */
  @POST
  @Path("/ListTransactions")
  @Produces(MediaType.APPLICATION_JSON)
  public Response listTransactions(String params) {
    logger.debug("[ListTransactions:Request]");
    Response response = Response.ok(Common.listTransactions(params)).build();
    logger.debug("[ListTransactions:Response] " + response.toString());
    return response;
  }

  /**
   * REST end-point for the {@link Common} currency methods.
   * 
   * @param params JSON representation of {@link CurrencyParameters}
   * @return Common.getBalance()
   */
  @POST
  @Path("/GetBalance")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getBalance(String params) {
    logger.debug("[GetBalance:Request]");
    Response response = Response.ok(Common.getBalance(params)).build();
    logger.debug("[GetBalance:Response] " + response.toString());
    return response;
  }

  /**
   * REST end-point for the {@link Common} currency methods.
   * 
   * @param params JSON representation of {@link CurrencyParameters}
   * @return Common.monitorBalance()
   */
  @POST
  @Path("/MonitorBalance")
  @Produces(MediaType.APPLICATION_JSON)
  public Response monitorBalance(String params) {
    logger.debug("[MonitorBalance:Request]");
    Response response = Response.ok(Common.monitorBalance(params, null)).build();
    logger.debug("[MonitorBalance:Response] " + response.toString());
    return response;
  }

  /**
   * REST end-point for the {@link Common} currency methods.
   * 
   * @param params JSON representation of {@link CurrencyParameters}
   * @return Common.prepareTransaction()
   */
  @POST
  @Path("/PrepareTransaction")
  @Produces(MediaType.APPLICATION_JSON)
  public Response prepareTransaction(String params) {
    logger.debug("[PrepareTransaction:Request]");
    Response response = Response.ok(Common.prepareTransaction(params)).build();
    logger.debug("[PrepareTransaction:Response] " + response.toString());
    return response;
  }

  /**
   * REST end-point for the {@link Common} currency methods.
   * 
   * @param params JSON representation of {@link CurrencyParameters}
   * @return Common.approveTransaction()
   */
  @POST
  @Path("/ApproveTransaction")
  @Produces(MediaType.APPLICATION_JSON)
  public Response approveTransaction(String params) {
    logger.debug("[ApproveTransaction:Request]");
    Response response = Response.ok(Common.approveTransaction(params, true)).build();
    logger.debug("[ApproveTransaction:Response] " + response.toString());
    return response;
  }

  /**
   * REST end-point for the {@link Common} currency methods.
   * 
   * @param params JSON representation of {@link CurrencyParameters}
   * @return Common.submitTransaction()
   */
  @POST
  @Path("/SubmitTransaction")
  @Produces(MediaType.APPLICATION_JSON)
  public Response submitTransaction(String params) {
    logger.debug("[SubmitTransaction:Request]");
    Response response = Response.ok(Common.submitTransaction(params)).build();
    logger.debug("[SubmitTransaction:Response] " + response.toString());
    return response;
  }
}
