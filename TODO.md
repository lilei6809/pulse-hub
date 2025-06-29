1. 现在我在完成 task7-添加缓存层, 实现了基本的缓存功能, 但是缓存需要考虑的问题很多, 比如用户更新profile后, 马上需要看到新的 profile, 这些需要我进一步了解缓存机制, 后期需要构建企业级缓存架构

   ```mermaid
   graph TD
       A["用户请求"] --> B{缓存中是否存在?}
       B -->|命中| C["从缓存返回数据"]
       B -->|未命中| D["查询数据库"]
       D --> E["将结果存入缓存"]
       E --> F["返回数据给用户"]
       
       G["数据更新请求"] --> H["更新数据库"]
       H --> I["清除对应缓存<br/>@CacheEvict"]
       I --> J["返回更新结果"]
       
       K["批量更新请求"] --> L["批量更新数据库"]
       L --> M["清除所有相关缓存<br/>@CacheEvict: allEntries=true"]
       M --> N["返回批量更新结果"]
       
       O["缓存维护"] --> P["手动清除缓存"]
       P --> Q["下次访问重建缓存"]
       
       classDef cacheHit fill:#4CAF50,stroke:#FFFFFF,font-size:14px,font-weight:bold,stroke-width:2px
       classDef cacheMiss fill:#FFC107,stroke:#FFFFFF,font-size:14px,font-weight:bold,stroke-width:2px
       classDef update fill:#F44336,stroke:#FFFFFF,font-size:14px,font-weight:bold,stroke-width:2px
       classDef maintenance fill:#2196F3,stroke:#FFFFFF,font-size:14px,font-weight:bold,stroke-width:2px
       
       class C cacheHit
       class D,E,F cacheMiss
       class G,H,I,J,K,L,M,N update
       class O,P,Q maintenance
   ```

   1. 