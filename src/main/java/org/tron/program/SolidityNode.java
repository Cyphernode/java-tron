package org.tron.program;

import com.typesafe.config.Config;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.tron.common.application.Application;
import org.tron.common.application.ApplicationFactory;
import org.tron.core.Constant;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.db.Manager;
import org.tron.core.exception.*;
import org.tron.core.services.RpcApiService;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.DynamicProperties;
import org.tron.protos.Protocol.Block;

@Slf4j
public class SolidityNode {

  private static GrpcClient grpcClient;
  private static Manager dbManager;

  public static void initGrpcClient(String addr) {
    grpcClient = new GrpcClient(addr);
  }

  public static void syncSolidityBlock() throws BadBlockException {
    while (true) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      DynamicProperties remoteDynamicProperties = grpcClient.getDynamicProperties();
      long remoteLastSolidityBlockNum = remoteDynamicProperties.getLastSolidityBlockNum();

      long lastSolidityBlockNum = dbManager.getDynamicPropertiesStore().getLatestSolidifiedBlockNum();
      if (lastSolidityBlockNum < remoteLastSolidityBlockNum) {
        Block block = grpcClient.getBlock(lastSolidityBlockNum + 1);
        try {
          BlockCapsule blockCapsule = new BlockCapsule(block);
          blockCapsule.generatedByMyself = true;
          dbManager.pushBlock(blockCapsule);
        } catch (ValidateScheduleException e) {
          throw new BadBlockException("validate schedule exception");
        } catch (ValidateSignatureException e) {
          throw new BadBlockException("validate signature exception");
        } catch (ContractValidateException e) {
          throw new BadBlockException("ContractValidate exception");
        } catch (ContractExeException e) {
          throw new BadBlockException("Contract Exectute exception");
        } catch (UnLinkedBlockException e) {
          throw new BadBlockException("Contract Exectute exception");
        }
      }

    }
  }

  /**
   * Start the SolidityNode.
   */
  public static void main(String[] args) throws InterruptedException {
    logger.info("SolidityNode running.");
    Args.setParam(args, Constant.NORMAL_CONF);
    Args cfgArgs = Args.getInstance();
    cfgArgs.setSolidityNode(true);

    ApplicationContext context = new AnnotationConfigApplicationContext(DefaultConfig.class);

    if (cfgArgs.isHelp()) {
      logger.info("Here is the help message.");
      return;
    }
    Application appT = ApplicationFactory.create(context);
    //appT.init(cfgArgs);
    RpcApiService rpcApiService = new RpcApiService(appT, context);
    appT.addService(rpcApiService);

    appT.initServices(cfgArgs);
    appT.startServices();
//    appT.startup();


    dbManager = appT.getDbManager();

    initGrpcClient(cfgArgs.getTrustNodeAddr());

    new Thread(() -> {
      try {
        syncSolidityBlock();
      } catch (BadBlockException e) {
        logger.error("Error in sync solidity block {}", e.getMessage());
      }
    }, logger.getName()).start();
    rpcApiService.blockUntilShutdown();
  }
}
