package com.iam;
 
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
 
import sailpoint.api.SailPointContext;
import sailpoint.api.SailPointFactory;
import sailpoint.object.QueryOptions;
import sailpoint.object.Rule;
import sailpoint.tools.GeneralException;
import sailpoint.tools.Util;
 
@SuppressWarnings({ "rawtypes", "unchecked" })
public class SailPointConcurrentService {
 
  private QueryOptions queryOptions;
  private Class objectClass;
  private String queryResultField = "id";
  private String ruleName;
  private List<Object> items;
  private Integer concurrentThreshold = 500;
 
  public SailPointConcurrentService(QueryOptions queryOptions, Class objectClass, String queryResultField, List<Object> items, String ruleName, Integer concurrentThreshold) {
    super();
    this.ruleName = ruleName;
    if (concurrentThreshold > 0) {
      this.concurrentThreshold = concurrentThreshold;
    }
    if (items != null) {
      this.items = items;
    } else {
      this.queryOptions = queryOptions;
      this.objectClass = objectClass;
      if (Util.isNotNullOrEmpty(queryResultField)) {
        this.queryResultField = queryResultField;
      }
    }
  }
 
  public void setQueryOptions(QueryOptions queryOptions) {
    this.queryOptions = queryOptions;
  }
 
  public void setObjectClass(Class objectClass) {
    this.objectClass = objectClass;
  }
 
  public void setQueryResultField(String queryResultField) {
    this.queryResultField = queryResultField;
  }
 
  public void setRuleName(String ruleName) {
    this.ruleName = ruleName;
  }
 
  public void setItems(List<Object> items) {
    this.items = items;
  }
 
  public void setConcurrentThreshold(Integer concurrentThreshold) {
    this.concurrentThreshold = concurrentThreshold;
  }
 
  public List<Object> execute(SailPointContext context) throws GeneralException {
    List<Object> results = new ArrayList<Object>();
    ExecutorService executorService = null;
    try {
      executorService = Executors.newFixedThreadPool(15);
      Collection<Callable<Object>> callables = new ArrayList<>();
 
      Iterator<Object> it = null;
      int counter = 0;
      List<Object> objects = new ArrayList<Object>();
      if (this.items != null) {
        it = this.items.iterator();
      } else {
        it = context.search(this.objectClass, this.queryOptions, this.queryResultField);
      }
      while (it.hasNext()) {
        if (counter >= this.concurrentThreshold) {
          System.out.println("Set:" + objects.size());
          callables.add(executeBundle(objects));
          objects = new ArrayList<Object>();
          counter = 0;
        }
        objects.add(it.next());
        counter++;
      }
      if (counter > 0) {
        System.out.println("Set:" + objects.size());
        callables.add(executeBundle(objects));
      }
      sailpoint.tools.Util.flushIterator(it);
 
      if (callables.size() > 0) {
        System.out.println("callables:" + (callables.size()));
        List<Future<Object>> taskFutureList = executorService.invokeAll(callables);
        for (Future<Object> future : taskFutureList) {
          Object resultObject = future.get(4, TimeUnit.SECONDS);
          if (resultObject != null) {
            results.add(resultObject);
          }
        }
      }
    } catch (InterruptedException e) {
      throw new GeneralException(e);
    } catch (ExecutionException e) {
      throw new GeneralException(e);
    } catch (TimeoutException e) {
      throw new GeneralException(e);
    } finally {
      executorService.shutdown();
    }
    return results;
  }
 
  private Callable<Object> executeBundle(List<Object> objects) throws GeneralException {
    return new Callable<Object>() {
      public Object call() throws Exception {
        Object result = null;
        SailPointContext myContext = null;
        try {
          myContext = SailPointFactory.createContext();
          HashMap params = new HashMap();
          params.put("context", myContext);
          params.put("partitionedObjects", objects);
          Rule driverRule = myContext.getObjectByName(Rule.class, ruleName);
          if (driverRule != null) {
            result = myContext.runRule(driverRule, params);
          }
        } catch (Exception e) {
          System.out.println("Warning: Problem executing Bundle: " + e.getMessage());
          throw new GeneralException(e);
        } finally {
          if (myContext != null) {
            myContext.decache();
            myContext.close();
          }
        }
        return result;
      }
    };
  }
 
  public static class Builder {
    private QueryOptions queryOptions;
    private Class objectClass;
    private String queryResultField = "id";
    private String ruleName;
    private List<Object> items;
    private Integer concurrentThreshold = 500;
 
    public Builder queryOptions(QueryOptions queryOptions) {
      this.queryOptions = queryOptions;
      return this;
    }
 
    public Builder objectClass(Class objectClass) {
      this.objectClass = objectClass;
      return this;
    }
 
    public Builder queryResultField(String queryResultField) {
      if (Util.isNotNullOrEmpty(queryResultField)) {
        this.queryResultField = queryResultField;
      }
      return this;
    }
 
    public Builder ruleName(String ruleName) {
      this.ruleName = ruleName;
      return this;
    }
 
    public Builder items(List<Object> items) {
      this.items = items;
      return this;
    }
 
    public Builder threshold(Integer concurrentThreshold) {
      if (concurrentThreshold != null && concurrentThreshold > 0) {
        this.concurrentThreshold = concurrentThreshold;
      }
      return this;
    }
 
    public SailPointConcurrentService build() {
      return new SailPointConcurrentService(this.queryOptions, this.objectClass, this.queryResultField, this.items, this.ruleName, this.concurrentThreshold);
    }
  }
}