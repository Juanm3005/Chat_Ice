# Application Architecture

<!-- mermaid-checked: no \n, no em-dash/en-dash, no {} in labels, subgraphs are id["label"], arrows are -->
~~~mermaid
flowchart TD
    subgraph ClientApp["Client application"]
        Console["Interactive console"]
        ChatUI["Chat commands and context"]
        FileTransfer["Chunked file transfer"]
        CallManager["Call signaling"]
        Voice["UDP voice audio"]
        Audio["Microphone and speakers"]
    end
    subgraph SharedApi["Shared contract"]
        Slice["Chat.ice"]
        IceTypes["Generated Ice types and proxies"]
    end
    subgraph ServerApp["Server application"]
        IceEndpoint["Ice endpoint TCP 10000"]
        ChatService["Chat and room service"]
        CallService["Call signaling service"]
        FileStore[("Server file storage")]
    end
    subgraph PeerClient["Other client"]
        PeerVoice["UDP voice audio"]
    end

    Console -->|"commands and output"| ChatUI
    ChatUI -->|"chat requests"| IceEndpoint
    ChatUI -->|"upload and download chunks"| FileTransfer
    FileTransfer -->|"file RPC calls"| IceEndpoint
    CallManager -->|"call setup and callbacks"| IceEndpoint
    CallManager -->|"starts and stops"| Voice
    Voice -->|"captures and plays"| Audio
    Voice <-->|"direct UDP audio"| PeerVoice
    Slice -->|"generates"| IceTypes
    IceTypes -.->|"shared by"| ChatUI
    IceTypes -.->|"shared by"| IceEndpoint
    IceEndpoint -->|"dispatches chat RPC"| ChatService
    IceEndpoint -->|"dispatches call RPC"| CallService
    ChatService -->|"reads and writes files"| FileStore
~~~
