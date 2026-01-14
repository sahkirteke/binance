//// file: XGBoostModel.java
//package com.binance.strategy;
//
//import ml.dmlc.xgboost4j.java.Booster;
//import ml.dmlc.xgboost4j.java.DMatrix;
//import ml.dmlc.xgboost4j.java.XGBoost;
//import ml.dmlc.xgboost4j.java.XGBoostError;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.stereotype.Component;
//
//import java.io.*;
//import java.util.*;
//import java.util.concurrent.ConcurrentHashMap;
//@Component
//public class XGBoostModel {
//    private static final Logger LOGGER = LoggerFactory.getLogger(XGBoostModel.class);
//
//    // Her sembol için ayrı model
//    private final Map<String, Booster> models = new ConcurrentHashMap<>();
//    private final Map<String, List<String>> featureNamesMap = new ConcurrentHashMap<>();
//    private final Map<String, Double> modelAccuracy = new ConcurrentHashMap<>();
//
//    // XGBoost 3.1.1 için güncel parametreler
//    private Map<String, Object> getDefaultParams() {
//        Map<String, Object> params = new HashMap<>();
//
//        // Binary classification
//        params.put("objective", "binary:logistic");
//        params.put("eval_metric", "logloss");
//        params.put("seed", 42);
//
//        // Ağaç parametreleri
//        params.put("max_depth", 6);
//        params.put("eta", 0.1);            // Learning rate
//        params.put("subsample", 0.8);      // Row sampling
//        params.put("colsample_bytree", 0.8); // Feature sampling
//        params.put("min_child_weight", 3);
//
//        // Regularization
//        params.put("gamma", 0.1);          // Minimum loss reduction
//        params.put("lambda", 1.0);         // L2 regularization
//        params.put("alpha", 0.0);          // L1 regularization
//
//        // Performance
//        params.put("tree_method", "hist"); // Histogram based (faster)
//        params.put("nthread", 4);          // Thread sayısı
//
//        return params;
//    }
//
//    // Model eğitimi
//    public TrainingResult train(String symbol, List<double[]> trainFeatures,
//                                List<Double> trainLabels,
//                                List<double[]> testFeatures,
//                                List<Double> testLabels,
//                                List<String> featureNames) {
//
//        LOGGER.info("Training XGBoost 3.1.1 model for {} with {} samples",
//                symbol, trainFeatures.size());
//
//        try {
//            // 1. Verileri 1D float[] formatına çevir (DMatrix row-major format kullanır)
//            float[] trainArray = convertTo1DFloatArray(trainFeatures);
//            float[] trainLabelsArray = convertLabelsToFloatArray(trainLabels);
//
//            float[] testArray = convertTo1DFloatArray(testFeatures);
//            float[] testLabelsArray = convertLabelsToFloatArray(testLabels);
//
//            // 2. DMatrix oluştur (XGBoost 3.1.1 formatı: float[], numRows, numCols)
//            int numTrainRows = trainFeatures.size();
//            int numCols = trainFeatures.get(0).length;
//            DMatrix trainMatrix = new DMatrix(trainArray, numTrainRows, numCols);
//            trainMatrix.setLabel(trainLabelsArray);
//
//            int numTestRows = testFeatures.size();
//            DMatrix testMatrix = new DMatrix(testArray, numTestRows, numCols);
//            testMatrix.setLabel(testLabelsArray);
//
//            // 3. Watchlist (monitoring için)
//            Map<String, DMatrix> watches = new HashMap<String, DMatrix>() {{
//                put("train", trainMatrix);
//                put("test", testMatrix);
//            }};
//
//            // 4. Parametreler
//            Map<String, Object> params = getDefaultParams();
//
//            // 5. Modeli eğit
//            int numRounds = 100;
//            Booster booster = XGBoost.train(
//                    trainMatrix,
//                    params,
//                    numRounds,
//                    watches,
//                    null,
//                    null
//            );
//
//            // 6. Modeli kaydet
//            models.put(symbol, booster);
//            featureNamesMap.put(symbol, featureNames);
//
//            // 7. Accuracy hesapla
//            double trainAcc = calculateAccuracy(booster, trainMatrix, trainLabels);
//            double testAcc = calculateAccuracy(booster, testMatrix, testLabels);
//            modelAccuracy.put(symbol, testAcc);
//
//            // 8. Feature importance al
//            Map<String, Integer> importance = getFeatureImportance(booster, featureNames);
//
//            LOGGER.info("✅ XGBoost trained for {}: Train={:.2f}%, Test={:.2f}%",
//                    symbol, trainAcc * 100, testAcc * 100);
//
//            return new TrainingResult(
//                    symbol, trainAcc, testAcc,
//                    trainFeatures.size(), testFeatures.size(),
//                    importance
//            );
//
//        } catch (Exception e) {
//            LOGGER.error("❌ XGBoost training failed for {}: {}", symbol, e.getMessage(), e);
//            return new TrainingResult(symbol, 0.0, 0.0, 0, 0, new HashMap<>());
//        }
//    }
//
//    // Tahmin yap
//    public PredictionResult predict(String symbol, double[] features) {
//        Booster booster = models.get(symbol);
//        if (booster == null) {
//            return new PredictionResult(0.5, false, "MODEL_NOT_TRAINED");
//        }
//
//        try {
//            // Tek satırlık 1D array oluştur
//            float[] singleRow = new float[features.length];
//            for (int i = 0; i < features.length; i++) {
//                singleRow[i] = (float) features[i];
//            }
//
//            DMatrix predictMatrix = new DMatrix(singleRow, 1, features.length);
//
//            // XGBoost 3.1.1'de predict 2D array döner
//            float[][] predictions = booster.predict(predictMatrix);
//
//            // Binary classification için ilk değeri al
//            float probability = predictions[0][0];
//            boolean willRise = probability > 0.55;
//            String confidence = String.format("%.1f%%", probability * 100);
//
//            return new PredictionResult(probability, willRise, confidence);
//
//        } catch (Exception e) {
//            LOGGER.error("Prediction failed for {}: {}", symbol, e.getMessage());
//            return new PredictionResult(0.5, false, "ERROR");
//        }
//    }
//
//    // Batch tahmin (çoklu örnek)
//    public List<PredictionResult> predictBatch(String symbol, List<double[]> featuresList) {
//        Booster booster = models.get(symbol);
//        List<PredictionResult> results = new ArrayList<>();
//
//        if (booster == null) {
//            LOGGER.warn("Model not found for {}", symbol);
//            return results;
//        }
//
//        try {
//            float[] batchArray = convertTo1DFloatArray(featuresList);
//            DMatrix batchMatrix = new DMatrix(batchArray, featuresList.size(),
//                    featuresList.get(0).length);
//
//            float[][] predictions = booster.predict(batchMatrix);
//
//            for (int i = 0; i < predictions.length; i++) {
//                float probability = predictions[i][0];
//                boolean willRise = probability > 0.55;
//                String confidence = String.format("%.1f%%", probability * 100);
//
//                results.add(new PredictionResult(probability, willRise, confidence));
//            }
//
//        } catch (Exception e) {
//            LOGGER.error("Batch prediction failed for {}", symbol, e);
//        }
//
//        return results;
//    }
//
//    // Modeli dosyaya kaydet
//    public void saveModel(String symbol, String filePath) throws IOException, XGBoostError {
//        Booster booster = models.get(symbol);
//        if (booster == null) {
//            throw new FileNotFoundException("Model not found for " + symbol);
//        }
//
//        // Modeli kaydet
//        booster.saveModel(filePath);
//
//        // Feature names'i kaydet
//        saveFeatureNames(symbol, filePath + ".features");
//
//        LOGGER.info("XGBoost model saved for {} to {}", symbol, filePath);
//    }
//
//    // Modeli dosyadan yükle
//    public void loadModel(String symbol, String filePath) throws IOException, XGBoostError {
//        Booster booster = XGBoost.loadModel(filePath);
//        models.put(symbol, booster);
//
//        // Feature names'i yükle
//        loadFeatureNames(symbol, filePath + ".features");
//
//        LOGGER.info("XGBoost model loaded for {} from {}", symbol, filePath);
//    }
//
//    // Feature importance hesapla
//    public Map<String, Integer> getFeatureImportance(Booster booster, List<String> featureNames) {
//        Map<String, Integer> importance = new HashMap<>();
//
//        try {
//            // XGBoost 3.1.1'de getFeatureScore - açıkça String parametresi ver
//            Map<String, Integer> scoreMap = booster.getFeatureScore((String) null);
//
//            if (scoreMap != null && !scoreMap.isEmpty()) {
//                for (Map.Entry<String, Integer> entry : scoreMap.entrySet()) {
//                    String featureId = entry.getKey();
//                    Integer score = entry.getValue();
//
//                    // f0, f1 gibi ID'leri feature isimlerine çevir
//                    if (featureId.startsWith("f")) {
//                        try {
//                            int index = Integer.parseInt(featureId.substring(1));
//                            if (index < featureNames.size()) {
//                                importance.put(featureNames.get(index), score);
//                            }
//                        } catch (NumberFormatException e) {
//                            // Skip invalid entries
//                        }
//                    }
//                }
//            }
//        } catch (Exception e) {
//            LOGGER.warn("Could not get feature importance: {}", e.getMessage());
//        }
//
//        return importance;
//    }
//
//    // Yardımcı metodlar
//
//    // 2D double[][] -> 1D float[] (row-major order)
//    private float[] convertTo1DFloatArray(List<double[]> doubleList) {
//        if (doubleList.isEmpty()) {
//            return new float[0];
//        }
//
//        int numRows = doubleList.size();
//        int numCols = doubleList.get(0).length;
//        float[] flatArray = new float[numRows * numCols];
//
//        for (int i = 0; i < numRows; i++) {
//            double[] row = doubleList.get(i);
//            for (int j = 0; j < numCols; j++) {
//                flatArray[i * numCols + j] = (float) row[j];
//            }
//        }
//
//        return flatArray;
//    }
//
//    // Labels: List<Double> -> float[]
//    private float[] convertLabelsToFloatArray(List<Double> doubleList) {
//        float[] floatArray = new float[doubleList.size()];
//
//        for (int i = 0; i < doubleList.size(); i++) {
//            floatArray[i] = doubleList.get(i).floatValue();
//        }
//
//        return floatArray;
//    }
//
//    private double calculateAccuracy(Booster booster, DMatrix data, List<Double> actualLabels)
//            throws XGBoostError {
//
//        float[][] predictions = booster.predict(data);
//        int correct = 0;
//
//        for (int i = 0; i < predictions.length; i++) {
//            float pred = predictions[i][0];
//            boolean predictedClass = pred > 0.5;
//            boolean actualClass = actualLabels.get(i) > 0.5;
//
//            if (predictedClass == actualClass) {
//                correct++;
//            }
//        }
//
//        return predictions.length > 0 ? (double) correct / predictions.length : 0.0;
//    }
//
//    private void saveFeatureNames(String symbol, String filePath) throws IOException {
//        List<String> names = featureNamesMap.get(symbol);
//        if (names != null) {
//            try (ObjectOutputStream oos = new ObjectOutputStream(
//                    new FileOutputStream(filePath))) {
//                oos.writeObject(names);
//            }
//        }
//    }
//
//    @SuppressWarnings("unchecked")
//    private void loadFeatureNames(String symbol, String filePath) throws IOException {
//        try (ObjectInputStream ois = new ObjectInputStream(
//                new FileInputStream(filePath))) {
//            List<String> names = (List<String>) ois.readObject();
//            featureNamesMap.put(symbol, names);
//        } catch (ClassNotFoundException e) {
//            throw new IOException("Failed to load feature names: " + e.getMessage(), e);
//        }
//    }
//
//    // Getter metodları
//    public boolean isModelTrained(String symbol) {
//        return models.containsKey(symbol);
//    }
//
//    public double getModelAccuracy(String symbol) {
//        return modelAccuracy.getOrDefault(symbol, 0.0);
//    }
//
//    public List<String> getFeatureNames(String symbol) {
//        return featureNamesMap.getOrDefault(symbol, new ArrayList<>());
//    }
//
//    // Record sınıfları
//    public record TrainingResult(
//            String symbol,
//            double trainAccuracy,
//            double testAccuracy,
//            int trainSamples,
//            int testSamples,
//            Map<String, Integer> featureImportance
//    ) {}
//
//    public record PredictionResult(
//            double probability,
//            boolean willRise,
//            String confidence
//    ) {}
//}