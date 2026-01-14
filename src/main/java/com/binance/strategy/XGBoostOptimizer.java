//// file: XGBoostOptimizer.java
//package com.binance.strategy;
//
//import org.springframework.stereotype.Component;
//
//import java.util.*;
//
//@Component
//public class XGBoostOptimizer {
//
//    // Hyperparameter tuning için farklı kombinasyonlar
//    public List<Map<String, Object>> generateParameterGrid() {
//        List<Map<String, Object>> grid = new ArrayList<>();
//
//        double[] learningRates = {0.01, 0.05, 0.1, 0.2};
//        int[] maxDepths = {3, 4, 5, 6, 7};
//        double[] subsamples = {0.6, 0.7, 0.8, 0.9};
//        double[] colsample = {0.6, 0.7, 0.8, 0.9};
//
//        for (double eta : learningRates) {
//            for (int depth : maxDepths) {
//                for (double subsample : subsamples) {
//                    for (double col : colsample) {
//                        Map<String, Object> params = new HashMap<>();
//                        params.put("objective", "binary:logistic");
//                        params.put("eval_metric", "logloss");
//                        params.put("max_depth", depth);
//                        params.put("eta", eta);
//                        params.put("subsample", subsample);
//                        params.put("colsample_bytree", col);
//                        params.put("seed", 42);
//                        params.put("tree_method", "hist");
//                        params.put("nthread", 4);
//
//                        grid.add(params);
//                    }
//                }
//            }
//        }
//
//        return grid;
//    }
//
//    // Early stopping callback
//    public Map<String, Object> getEarlyStoppingParams(int rounds) {
//        Map<String, Object> params = new HashMap<>();
//        params.put("early_stopping_rounds", 10);
//        params.put("max_rounds", rounds);
//        return params;
//    }
//}